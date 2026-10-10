# 녹음 시작·종료 부하테스트: AFTER_COMMIT + REQUIRES_NEW 커넥션 고갈 (#238)

2026-10-09~10 로컬 실험 기록 (`perf/#238-be-load-test`). 코드 수정(`@Async`)은 검증 후 되돌렸고 아직 반영 전이다.

## 1. 결론

- 녹음 시작·종료가 동시에 **커넥션 풀 크기 이상** 몰리면 요청이 30초씩 멈추고, 풀 크기를 넘는 요청은 실패한다.
- 원인: AFTER_COMMIT 리스너가 원래 트랜잭션의 커넥션을 반납하기 전에 `REQUIRES_NEW`로 커넥션을 하나 더 요청한다. 요청 1개가 커넥션 2개를 쓰는 구조라 풀이 바닥나면 서로 기다리다(Pool 데드락) `connection-timeout`(30초) 뒤 실패한다.
- 후속 작업 실패는 커밋 뒤라 로그만 남고 삼켜진다. 응답은 성공인데 **알림·요약 등록·화자 분리 등록·챗봇 취소가 조용히 사라진다.**
- 해당 리스너에 `@Async`를 붙여 요청 Thread와 분리하면 풀 10으로 동시 50까지 시작·종료 모두 100% 성공, 풀 대기 시간 초과 0회.

## 2. 문제 코드 경로

### 녹음 시작

1. `RecordingService.start` (`@Transactional`) — 커넥션 A 사용, `RecordingStartedEvent` 발행
2. `NotificationEventListener.createMeetingStartedNotification` (`AFTER_COMMIT`) — A를 아직 쥔 상태
3. `NotificationService.notifyActiveTeamMembers` — 팀원마다 `create` 호출
4. `NotificationWriter.create` (`REQUIRES_NEW`) — **커넥션 B 요청, 여기서 대기**

### 녹음 종료

1. `RecordingService.updateStatus` (COMPLETED) — 커넥션 A
2. `ChatCancelListener.cancelProcessingChats` (`AFTER_COMMIT`) → `ChatProcessingService.cancelProcessing` (`REQUIRES_NEW`)
3. AI 연결이 없으면 `AiLiveMeetingStopListener`가 `MeetingTranscriptFinalizedEvent`를 같은 Thread에서 발행
   → `SummaryAutoRegistrationListener` / `DiarizationAutoRegistrationListener` → 각각 `REQUIRES_NEW`

같은 패턴: `NotificationEventListener` 나머지 3개(요약 완료·크레딧 적립·팀원 가입), `AudioFileDeleteListener` → `AudioFileService.completeDelete`(`REQUIRES_NEW`, S3 삭제 포함).

### 왜 커넥션을 쥐고 있나

Spring 커밋 순서 (`AbstractPlatformTransactionManager.processCommit`, spring-tx 7.0.9):

| 줄 | 단계 |
| --- | --- |
| 794 | `doCommit` — DB COMMIT |
| 835 | `triggerAfterCommit` — **AFTER_COMMIT 리스너 실행** |
| 838 | `triggerAfterCompletion` |
| 846 → 1061 | `cleanupAfterCompletion` → `doCleanupAfterCompletion` |

`JpaTransactionManager.doCleanupAfterCompletion`(spring-orm 7.0.9, 609줄) 안 626줄 `releaseJdbcConnection`에서 커넥션이 반납된다. AFTER_COMMIT 리스너는 반납 전에 실행되므로, 그 안의 `REQUIRES_NEW`는 기존 커넥션을 보류(suspend)한 채 새 커넥션을 받는다.

## 3. 실험 환경

| 항목 | 값 |
| --- | --- |
| BE | 로컬 docker 컨테이너 1대, `--cpus=1 --memory=2g`, local 프로필 |
| DB / Redis | docker compose MySQL 8.4, Redis 7 (호스트) |
| Hikari | `maximum-pool-size` 10(기본, dev와 동일) / 20, `connection-timeout` 30초(기본) |
| 부하 도구 | k6 v1.6.1, `per-vu-iterations`로 동시 요청 |
| 데이터 | 회차마다 사용하지 않은 팀(리더 1명)으로 회의 생성 → 참여 → 측정 |
| AI | 가짜 HTTP AI(8001), 가짜 실시간 WS AI(8000). 시작·종료 측정은 AI WS 미연결 상태 |

이미지

| 이름 | 내용 |
| --- | --- |
| A | 부모 브랜치(챗봇 + dev 머지) 그대로 |
| B | A에서 `createMeetingStartedNotification`만 즉시 return (worktree에서만 수정) |
| ASYNC | A에서 아래 5곳에 `@Async(APPLICATION_TASK_EXECUTOR_BEAN_NAME)` 추가 |

ASYNC 대상: `NotificationEventListener` 메서드 4개, `ChatCancelListener`, `SummaryAutoRegistrationListener`, `DiarizationAutoRegistrationListener`, `AudioFileDeleteListener`. 전체 테스트 840개 통과.

## 4. 결과

### 녹음 동시 시작 (`POST /meetings/{id}/recordings`)

| 구성 | 동시 1 | 동시 10 | 동시 30 | 동시 50 |
| --- | --- | --- | --- | --- |
| A 풀 10 | 1/1, 134ms | 10/10, **31초** | **10/30**, 30초 | **10/50**, 30초 |
| B 풀 10 (알림 끔) | 1/1, 48ms | 10/10, 최대 0.28초 | 30/30, 최대 0.64초 | 50/50, 최대 0.92초 |
| A 풀 20 | 1/1, 133ms | 10/10, 0.32초 | **20/30**, 30초 | **20/50**, 30초 |
| ASYNC 풀 10 | 1/1, 75ms | 10/10, 최대 0.17초 | 30/30, 최대 1.1초 | 50/50, 최대 0.73초 |

풀 대기 시간 초과 로그(`Connection is not available`) 횟수

| 구성 | 1 | 10 | 30 | 50 |
| --- | --- | --- | --- | --- |
| A 풀 10 | 0 | 30 | 104 | 190 |
| B 풀 10 | 0 | 0 | 0 | 0 |
| A 풀 20 | 0 | 0 | 97 | 174 |
| ASYNC 풀 10 | 0 | 0 | 0 | 0 |

ASYNC에서 회의 시작 알림 50/50 생성 확인(비동기로 옮겨도 누락 없음).

### 녹음 동시 종료 (`PATCH /recordings/{id}` COMPLETED, AI 미연결)

| 구성 | 동시 1 | 동시 10 | 동시 30 | 동시 50 |
| --- | --- | --- | --- | --- |
| A 풀 10 | 1/1, 112ms | 10/10, **60초** | **10/30**, 최대 60초 | **10/50**, 최대 60초 |
| ASYNC 풀 10 | 1/1, 106ms | 10/10, 최대 0.48초 | 30/30, 최대 0.87초 | 50/50, 최대 0.72초 |

| 구성 | 요약·화자 분리 등록 (동시 1 / 10 / 30 / 50) | 풀 대기 시간 초과 |
| --- | --- | --- |
| A 풀 10 | 1 / **0** / **1** / **4** | 0 / 68 / 145 / 216 |
| ASYNC 풀 10 | 1 / 10 / 30 / 50 | 0 / 0 / 0 / 0 |

종료가 60초인 이유: 요청 하나가 `ChatCancelListener`와 요약 등록에서 각각 30초씩 커넥션을 기다린다.

### 실제 녹음 흐름 (참고, A 이미지 기준)

녹음 시작 → 오디오 WS → AI 실시간 WS 연결 → 4KB 청크 1초 간격 20개 → 종료, 10팀 동시.

| 항목 | 결과 |
| --- | --- |
| 흐름 성공 | 10/10 (청크 200개 ACK, 전사 50건 저장, 요약·화자 분리 각 10/10) |
| WS 연결 → `stream.ready` | 평균 0.87초 (가짜 AI 준비 지연 0.7초 포함) |
| CPU / 메모리 최고 | 40% / 483MiB |
| 녹음 종료 | 10건 모두 30.6초 (`ChatCancelListener` 대기) |

녹음 스트리밍 자체는 이 규모에서 여유가 있다. dev 부하테스트(10.06)의 `decoder.ready` 5초 초과는 BE가 아니라 AI 쪽 준비 시간 문제로 본다.

### 재측정 + 모니터링 (10.10, A 이미지, 녹음 동시 시작)

컨테이너에 관리 포트(8081)를 열고 1초 간격 Prometheus로 수집했다.

| 동시 | 성공 | 응답 시간 (평균 / 최대) | 풀 대기 시간 초과 로그 |
| --- | --- | --- | --- |
| 1 | 1/1 | 100ms | 0 |
| 10 | 10/10 | 30.8초 / 30.8초 | 30 |
| 30 | 10/30 | 30.5초 / 30.7초 | 110 |
| 50 | 10/50 | 30.3초 / 30.4초 | 187 |

| 지표 (측정 구간 최대) | 값 | 의미 |
| --- | --- | --- |
| `hikaricp_connections_active` | 10 | 풀 전부 사용 |
| `hikaricp_connections_pending` | 52 | 커넥션 대기 요청 (동시 50 + 스케줄러) |
| `hikaricp_connections_acquire_seconds_max` | 30.1초 | `connection-timeout`까지 대기 |
| `hikaricp_connections_usage_seconds_max` | 30.8초 | 커넥션 A를 쥔 채 B를 기다린 시간 |
| `tomcat_threads_busy_threads` | 50 | 요청 Thread 전부 묶임 |

`active` 10 고정 + `pending` 급증 + 점유 30초가 동시에 나타나 커넥션 풀 고갈이 지표로 확인된다.

## 5. 해석

- **원인 확정**: 알림만 끈 B는 동시 50까지 성공, 시간 초과 0회.
- **성공 건수 = 풀 크기**: 풀 10이면 10건, 20이면 20건. 풀 크기만큼은 커넥션 2개를 받아 끝까지 가고, 나머지는 바깥 트랜잭션조차 시작하지 못한다. 풀 증설은 한계를 뒤로 미룰 뿐이다.
- **동시 10의 31초 성공**: 실패가 아니라 정체. 10개가 커넥션 10개를 모두 쥐고 서로를 기다리다 30초 뒤 후속 작업이 실패해야 응답이 나간다.
- **#238 관측과 일치**: 동시 30에서 20건, 50에서 40건 실패.

## 6. `@Async`가 해결하는 이유

목적은 빠른 응답이 아니라 **Thread 분리**다.

- 수정 전: 요청 Thread가 A를 쥔 채 리스너 안에서 B를 기다린다.
- 수정 후: 리스너는 작업을 `applicationTaskExecutor` Queue에 넣고 끝난다 → 요청 Thread가 바로 cleanup 단계로 가서 A를 반납 → 별도 Thread(`task-N`)가 B만 받아 처리한다. 커넥션을 동시에 2개 쥐는 Thread가 없어진다.

## 7. 반영 전 검토할 점

1. **이벤트 순서**: `ChatCancelListener`가 비동기가 되면 `MEETING_COMPLETED` SSE 종료보다 늦게 실행될 수 있어, 진행 중이던 질문의 `CHAT_FAILED`가 닫힌 SSE로 나가 화면에 안 보일 수 있다. DB 상태(실패·크레딧 복구)는 정상.
2. **Executor 한계**: `applicationTaskExecutor`는 Thread 8개, Queue 무제한. 부하가 몰리면 후속 작업이 지연된다. (`improvement-points.md` #1과 연결)
3. **재시작 시 유실**: Queue는 메모리라 실행 전에 서버가 내려가면 알림·등록 작업이 사라진다. 확실히 막으려면 outbox(테이블 기록 + 스케줄러)까지 가야 한다.
4. 대안: 알림을 원래 트랜잭션 안(BEFORE_COMMIT)에서 같이 저장하는 방식도 Thread 분리 없이 커넥션 1개로 끝난다. 다만 알림 실패가 녹음 시작 실패로 번진다.

## 8. 재현 방법

스크립트: `scripts/`, Grafana 대시보드: `grafana-pool-dashboard.json`

| 파일 | 용도 |
| --- | --- |
| `make_token.py <userId>` | local JWT secret으로 access token 생성 |
| `fake_ai.py` | 가짜 HTTP AI (`POST /v1/chat`, 8001) |
| `fake_live.py` | 가짜 실시간 WS AI (8000, `pip install websockets` 필요) |
| `ab_run.sh <label> <image> <pool> <offset>` | 녹음 동시 시작 1/10/30/50 (`END=1`이면 마지막 50건 동시 종료 추가) |
| `end_run.sh <label> <image> <pool> <offset>` | 녹음 동시 종료 1/10/30/50 |
| `start_n.js`, `end_n.js`, `rec_flow.js` | k6 시나리오 |

```bash
docker compose up -d mysql redis prometheus grafana
docker build -t meety-be:a .

cd load-test/issue-238/after-commit-requires-new/scripts
# 사용하지 않은 팀 목록 (team_id user_id), 회차마다 이 목록에서 순서대로 사용
docker exec meety-mysql mysql -uroot -pmeety meety -N -e "
  select tm.team_id, tm.user_id from team_members tm
  join team_credits tc on tc.team_id = tm.team_id
  where tm.role='LEADER' and tm.membership_status='ACTIVE' and tc.balance >= 20
    and not exists (select 1 from meetings m where m.team_id = tm.team_id
                    and m.status='IN_PROGRESS' and m.deleted_at is null)
  order by tm.team_id limit 300" > free_teams.txt

./ab_run.sh A meety-be:a 10 0
./end_run.sh A meety-be:a 10 91
```

### 모니터링

- `docker run`에 `-p 8081:8081`이 들어 있어 actuator 지표가 열린다.
- 기본 Prometheus(9090)는 15초 간격이라 30초 현상이 거칠게 보인다. 1초 간격 Prometheus를 따로 띄워 `host.docker.internal:8081/actuator/prometheus`를 수집하고 Grafana 데이터소스로 등록한 뒤 쓴다.
- `grafana-pool-dashboard.json`의 datasource `uid`를 그 데이터소스 uid로 바꿔 import한다.

```bash
curl -u admin:admin -H "Content-Type: application/json" -X POST \
  localhost:3001/api/dashboards/db --data-binary @grafana-pool-dashboard.json
```
