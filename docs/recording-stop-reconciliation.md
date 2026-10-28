# Redis 장애 중 유실된 녹음 STOP 보정 (FIX #379)

## 배경과 결정

녹음 종료 API는 MySQL의 `RecordingSession`과 `Meeting`을 `COMPLETED`로 커밋한 다음 `AFTER_COMMIT` 리스너에서 Redis Owner를 조회하고 소유 BE에 STOP을 전달한다. Redis 조회·Pub/Sub이 실패해도 DB 커밋은 유지된다. [실험 이슈 #378](https://github.com/100-hours-a-week/KTB4-6th-BE/issues/378)의 두 BE + Fake AI 실험에서는 이때 AI 연결이 `READY`로 남고 `session.stop`이 전달되지 않았다.

선택한 복구 방식은 **AI 연결을 가진 BE의 로컬 연결 목록을 주기적으로 MySQL Primary 상태와 대조**하는 것이다. 인스턴스별 연결 ID만 조회해 100개씩 상태를 읽으므로 모든 녹음 세션을 스캔하지 않는다. DB가 `COMPLETED`여도 Redis Owner가 해당 로컬 연결의 `serverId:connectionId`와 일치할 때만 기존 STOP 경로를 실행한다. Redis가 일시적으로 접근 불가하면 다음 주기에 재시도한다. Owner가 없거나 다르면 안전하게 보류한다.

| 대안 | Redis 장애 중 사용 | 현재 구조에서의 판단 |
| --- | --- | --- |
| 로컬 연결 + Primary 조회 | 가능. STOP 직전 Owner 확인은 Redis 복구 필요 | 기존 ConnectionRegistry·STOP·전사 확정 경로 재사용, 작은 변경 |
| DB Outbox + Worker | 가능 | 전달 내구성을 강화하지만, Worker가 Redis 없이 현재 연결 소유 BE를 찾는 문제와 중복·순서 정책이 추가됨 |
| BE 간 직접 HTTP | 주소를 별도 확보하면 가능 | Redis 장애 시 소유 BE 주소와 연결 세대를 식별할 방법이 현재 없음 |

## 처리 흐름과 안전 조건

1. 전용 단일 스레드 스케줄러가 로컬 `AiLiveMeetingConnectionRegistry`에서 `READY`·`PAUSED`·`PAUSE_SENT`·`RESUME_SENT` 연결만 후보로 고른다. `STOP_SENT`·`ENDED`·`CLOSED`·Recovery-fenced 연결은 제외한다.
2. `RecordingStatusPrimaryReader`가 `@Transactional`(readOnly가 아님)로 100개 단위 상태를 조회한다. 현재 라우팅 DataSource에서 이 트랜잭션은 write/Primary 풀로 간다. 스칼라 DTO를 반환하고 트랜잭션을 닫은 후 Redis와 AI I/O를 수행한다.
3. DB `COMPLETED`, meetingId 일치, 같은 로컬 ConnectionRegistry 객체를 확인한다. Redis Owner가 로컬 연결의 Owner와 같아야 한다. Owner가 없거나 읽기 실패·불일치면 STOP을 보류하고 카운터를 올린다. Owner를 `SET`으로 재등록하지 않는다.
4. `stopCurrent`가 연결 객체와 Owner를 다시 확인하고, 연결의 송신 락 안에서도 Owner·Recovery fence를 확인한 뒤 기존 `session.stop`을 한 번 전송한다. 정상 Pub/Sub과 보정이 경합해도 `STOP_SENT` 또는 `stopRequested`가 두 번째 전송을 막는다.
5. 기존 `session.ended`/30초 종료 타임아웃 경로가 전사 확정과 연결 정리를 수행한다. 연결당 전사 확정 시작은 원자 플래그로 한 번만 허용한다. `session.stop` 전송 성공과 AI의 `session.ended` 수신은 별개로 관측한다.

설정은 `RECORDING_STOP_RECONCILIATION_ENABLED`(기본 `false`)와 `RECORDING_STOP_RECONCILIATION_INTERVAL`(기본 `3s`)이다. 주기는 테스트에서 `1s`·`3s`·`5s`로 바꿀 수 있다. 이번 로컬 실험은 `3s`로 수행했으며 운영 주기는 부하 측정 전까지 확정하지 않는다. 보정 스케줄러는 연결 유지용 스케줄러와 별도 1스레드 풀을 사용한다.

Micrometer 카운터: `recording.stop.reconciliation.scans`, `db.queries`, `db.failures`, `detected`, `owner.unavailable`, `owner.mismatch`, `started`, `failures`. `started`는 보정 STOP 경로가 시작됐다는 뜻이며 AI 종료 확인 수는 아니다. `db.queries`는 코드 실행당 조회 시도 수를 세며, 아래 실험에서 별도로 수집하지 않았다.

## 로컬 재검증 결과 (2026-10-28 UTC)

조건: 기존 실험 Compose의 MySQL·Redis Sentinel 3대·Fake AI·모의 FE를 그대로 사용했다. BE A/B만 최신 FIX JAR로 임시 교체하고 보정 기능을 켰다. 외부 AI 또는 실제 브라우저 FE 결과가 아니다. 각 행은 별도의 녹음 세션이다. 원본 로그는 기존 실험 worktree의 `experiments/redis-sentinel/results/`에 보존했다.

| 상황 | 기존 코드 | FIX JAR, 3초 주기 |
| --- | --- | --- |
| 정상 STOP | 기준 동작 | 세션 14: BE B HTTP 200, Fake AI `session.ended` 1건, FE WS 정상 종료, 전사 15건 조회. 보정 로그 0건. |
| Master 장애 중 STOP | 세션 9: DB `COMPLETED`, `session.ended` 0건, FE 종료 미관측 | 세션 15: BE B Redis timeout·HTTP 200, BE A Owner 확인 불가 후 재시도, 12:45:37.506 보정, 12:45:37.507 AI `session.ended`, FE 정상 종료, 전사 21건 조회. DB 종료 시각 12:45:30.071 → AI 종료 약 7.44초. |
| BE B만 Redis 단절 | 세션 10: DB `COMPLETED`, `session.ended` 0건, FE 종료 미관측 | 세션 16: BE B Redis timeout·HTTP 200, BE A 12:46:07.546 보정, 12:46:07.547 AI `session.ended`, FE 정상 종료, 전사 13건 조회. DB 종료 시각 12:46:06.885 → AI 종료 약 0.66초. BE B의 Redis 연결 복구(12:46:18.321) 전 종료됨. |

세션 15·16의 Fake AI 로그에는 `session_ended`가 각각 1건이다. 세션 14의 정상 STOP에서도 1건이며 보정 로그는 없다. 이는 이번 단일 실행에서 중복 STOP이 관측되지 않았다는 근거이며, 모든 경합을 실험으로 증명한 것은 아니다. 자동화 테스트는 정상 STOP과 보정 경합, Owner 불일치, 종료 중 상태를 별도로 검사한다.

## 한계와 후속 검증

- Redis Master가 복구되기 전에는 전역 Owner를 검증할 수 없어 보정하지 않는다. Owner Key가 소실되면 자동 재등록하지 않으므로 이 방식만으로는 종료를 보장하지 못한다. 별도 소유권·fencing 정책이 필요하다.
- Redis Owner를 확인한 직후 다른 Owner로 교체되는 시간차 경합은 전역 원자적 fencing이 아니다. 로컬 연결 교체·Recovery fence는 다시 확인하지만, 서버 간 세대 전환의 완전한 원자성은 별도 설계가 필요하다.
- STOP 전송 실패 시 기존 경로는 타임아웃/전사 확정으로 정리한다. `session.ended`가 없는 경우 마지막 전사 누락 가능성이 있다. 전사 확정 큐 자체의 실패·포화 복구는 이번 이슈 범위 밖이다.
- 현재 실험은 Fake AI와 모의 FE를 사용한다. 실제 AI 공급자, 브라우저, 동시 녹음 N건, 1초·5초 주기의 DB 부하 및 감지 지연은 미측정이다.
- PAUSE·RESUME 자동 보정, Owner 재등록, Sentinel 운영 도입은 포함하지 않았다.

## Issue #379에 반영할 체크리스트

- [x] 최신 `origin/dev`에서 별도 FIX worktree/브랜치 생성
- [x] 로컬 연결 + MySQL Primary 상태 조회 기반 STOP 보정 구현
- [x] Redis Owner 일치·Recovery fence·동일 연결 객체 확인
- [x] 정상 STOP과 보정 STOP 중복 전송 및 전사 확정 시작 방지
- [x] 설정 토글·주기·전용 스케줄러·카운터 추가
- [x] 전체 로컬 테스트 및 JAR 빌드 통과
- [x] 두 BE Fake AI 정상/Master 장애/BE B 단절 실험에서 STOP 복구 확인
- [ ] 실제 브라우저 FE·외부 AI 연동 검증
- [ ] 동시 녹음 부하에서 1초·3초·5초 주기와 Primary 조회 비용 측정
- [ ] Owner 소실 및 전역 소유권 전환을 위한 별도 정책 수립
