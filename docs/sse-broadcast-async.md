# Issue #210: Meeting SSE 실행 경계 분리

기준: `origin/dev`의 `488635c` (`feat/v2-dashboard (#300)`), 2026-10-18.

## 확인한 기존 경로

```text
AI transcript.committed → 전사 DB Commit → AFTER_COMMIT → Redis PUBLISH

공유 RedisMessageListenerContainer (SyncTaskExecutor)
  → MeetingSseSubscriber → MeetingSseRegistry → 참가자별 send
```

AI 수신 스레드는 참가자 send를 직접 호출하지 않는다. 문제는 Redis 수신 이후의 동기 전송이다.
`meeting-sse`, `notification-sse`, 녹음 제어 명령은 같은 Redis 리스너 컨테이너를 사용한다.
기존 `notificationTaskExecutor`는 알림 생성 이벤트의 `@Async`에서 사용하며,
`NotificationRedisSubscriber`의 SSE 전송에는 사용하지 않는다.

프로덕션 변경 전에 현재 `MeetingSseSubscriber`와 실제 로컬 Redis, 실제 `RedisConfig`로 재현했다.
이후 비교 테스트의 동기 대조군은 변경 전 BROADCAST 경로를 테스트 내부에만 유지한다.
`MeetingSseLoopback`은 도메인 payload/정리 검증용이며, 스레드 격리의 근거로 사용하지 않았다.

## 변경한 경로와 순서

```text
Redis 수신 → 역직렬화 → MeetingSseDispatcher.dispatch → 반환
                              ↓
                    meetingId별 ArrayDeque
                              ↓
                    공용 ThreadPoolTaskExecutor
                              ↓
                    Registry → 순차 emitter.send
```

`BROADCAST`, `COMPLETE_PARTICIPANT`, `COMPLETE_ALL`, `SEND_AND_COMPLETE_ALL`은 모두 같은 회의 큐를 거친다.
보장 범위는 이 인스턴스가 수신해 수락한 이벤트의 순서이다. 분산 전체의 논리적 순서나 sequence 재정렬은 제공하지 않는다.

하나의 짧은 monitor가 map 조회/생성, enqueue, poll, pending 수, 빈 큐 제거를 보호한다.
네트워크 전송은 monitor 밖에서 실행한다. map에 존재하는 회의에는 runner를 추가 제출하지 않는다.
runner가 전송을 마친 후 빈 큐를 제거하는 작업과 새 enqueue가 같은 monitor를 사용하므로,
제거된 옛 큐에 이벤트가 남거나 같은 회의가 두 runner에서 동시에 전송되는 경쟁을 막는다.
명령의 RuntimeException은 기록하고 다음 명령으로 진행한다.

## 초기 설정과 포화 정책

아래 값은 서비스 부하 측정으로 확정한 수치가 아닌 **초기 가정값**이다.
worker 4/4와 Executor queue 100은 기존 notification Executor 설정에 맞췄다.
회의별 pending 100은 동일한 대기열 규모, 전체 pending 1,000은 기존 chat queue 규모를 초기 예산으로 참고했다.
별도 SSE 부하 테스트로 재조정해야 한다. 회의마다 Executor/스레드를 생성하지 않는다.

| 설정 | 기본값 | 환경 변수 |
|---|---:|---|
| core pool | 4 | `MEETING_SSE_EXECUTOR_CORE_POOL_SIZE` |
| max pool | 4 | `MEETING_SSE_EXECUTOR_MAX_POOL_SIZE` |
| Executor queue capacity | 100 | `MEETING_SSE_EXECUTOR_QUEUE_CAPACITY` |
| 회의별 pending 상한 | 100 | `MEETING_SSE_MAX_PENDING_PER_MEETING` |
| 서버 전체 pending 상한 | 1,000 | `MEETING_SSE_MAX_PENDING_TOTAL` |
| 종료 시 작업 완료 대기(초) | 10 | `MEETING_SSE_EXECUTOR_SHUTDOWN_AWAIT_SECONDS` |

pending은 **대기 중 이벤트**만 센다. 실행 중인 이벤트는 제외한다.
따라서 기본 설정에서 대기 이벤트는 최대 1,000개, 실행 중 이벤트는 최대 4개이다.
Executor queue는 개별 이벤트가 아니라 실행을 기다리는 회의 runner의 대기열이다.

회의별/전체 한도 초과 시 새 이벤트를 거절한다. Executor가 새 회의 runner를 거절하면
방금 만든 큐와 pending 증가를 되돌린다. 모든 거절은 사유별 Counter와 WARN 로그에 기록한다.
이미 수락한 이벤트는 순서를 유지하며, 거절된 이벤트를 호출 스레드에서 실행하거나 자동 재시도하지 않는다.
`AbortPolicy`를 명시하며 `CallerRunsPolicy`를 사용하지 않는다.

**종료 명령도 포화 시 거절될 수 있다.** 별도 우회/우선 실행은 순서를 깨므로 도입하지 않았다.
이는 무손실 전송 정책이 아니며, 거절된 전사의 원본은 DB에서 조회할 수 있다.
자동 replay/FE 재조회는 이번 변경에 포함하지 않았다.

큐는 작업이 모두 끝나면 map에서 제거한다. emitter가 없어도 이미 받은 명령은 순서대로 처리한 후 큐를 정리한다.
종료 시 신규 제출을 차단하고 Executor에 완료 대기 시간을 부여한다.
이 대기 시간은 개별 send timeout이나 프로세스 종료 시 전송 보장을 의미하지 않는다.

## 관측 지표

meetingId/userId를 metric tag로 넣지 않아 cardinality를 제한한다.

| Micrometer 이름 | 의미 |
|---|---|
| `meeting.sse.subscriber` | Subscriber 진입부터 역직렬화·제출·반환까지 |
| `meeting.sse.queue.wait` | 수락된 이벤트의 enqueue부터 worker poll까지 |
| `meeting.sse.broadcast` | BROADCAST 및 SEND_AND_COMPLETE_ALL 실행 시간 |
| `meeting.sse.emitter.send` | 개별 emitter의 실제 send 호출 시간(실패 포함) |
| `meeting.sse.emitter.failures` | 개별 send 예외 수 |
| `meeting.sse.command.failures` | runner에서 발생한 명령 실행 예외 수 |
| `meeting.sse.workers.active` | 활성 SSE worker 수 |
| `meeting.sse.pending.events` | 대기 이벤트 수 |
| `meeting.sse.queues` | 실행 중/대기 중인 회의 큐 수 |
| `meeting.sse.executor.queued.tasks` | Executor에서 대기하는 회의 runner 수 |
| `meeting.sse.rejected{reason}` | `meeting-capacity`, `total-capacity`, `executor`, `closed`, `invalid` 사유별 거절 수 |

기존 Actuator/Micrometer/Prometheus 구성을 재사용한다. 새 의존성은 추가하지 않았다.

## 실제 재현 및 비교 측정

로컬 Redis `7-alpine`, Java 25, 동일 Redis 컨테이너의 별도 meeting/notification 채널을 사용했다.
send 내부 latch를 약 500ms 유지했다. 브라우저/인터넷을 통한 실제 느린 클라이언트 벤치마크는 아니다.
비동기 통합 테스트는 worker 2개를 사용한다. 각 시나리오 3회 중앙값이며 운영 p95/p99나 처리량 추정치가 아니다.
B와 Notification의 지연은 발행 직전부터 해당 emitter send 진입까지 측정한다.

프로덕션 리팩터링 **전** 실제 Subscriber 측정:

| 지표 | 3회 원자료(ms) | 중앙값(ms) |
|---|---|---:|
| Redis receive → Subscriber return | 523.985 / 508.073 / 508.422 | 508.422 |
| 느린 A가 있을 때 B 처리 | 509.185 / 507.791 / 508.022 | 508.022 |
| 느린 A가 있을 때 Notification 처리 | 512.443 / 508.321 / 508.285 | 508.321 |
| A 개별 send | 508.473 / 507.274 / 507.579 | 507.579 |

개선 후 최종 전체 테스트 실행의 실제 Subscriber 측정:

| 지표 | 3회 원자료(ms) | 중앙값(ms) |
|---|---|---:|
| Redis receive → Subscriber return | 0.758 / 0.902 / 1.302 | 0.902 |
| 느린 A가 있을 때 B 처리 | 1.068 / 1.527 / 1.361 | 1.361 |
| 느린 A가 있을 때 Notification 처리 | 2.032 / 2.482 / 2.212 | 2.212 |
| A 개별 send | 503.584 / 503.274 / 505.141 | 503.584 |
| 회당 queue wait 최대값 | 0.159 / 0.134 / 0.195 | 0.159 |
| 회당 broadcast 최대값 | 503.618 / 503.308 / 505.190 | 503.618 |
| 회당 개별 send 최대값 | 503.595 / 503.286 / 505.157 | 503.595 |

A가 막혀 있고 B/Notification 전송이 끝난 시점의 active worker는 1, pending은 0, 거절은 0이었다.
회의별/전체/Executor 포화 단위 테스트에서는 각 거절 사유별 Counter 1 증가와 pending/map 원복을 검증했다.
같은 회의의 순차 fan-out 자체는 그대로이며 Test A에서 100ms 대기를 추가했을 때 broadcast도 그만큼 대기했다.

## 테스트와 기존 실패

- 신규 테스트: blocking 재현 1건, Dispatcher 13건(동시 enqueue/cleanup 5회 반복 포함), 설정 검증 8건, 실제 Redis 비교 6건.
- 같은 회의의 4종 명령 순서, 실제 send 이전 COMPLETE 실행 방지, 다른 회의 병렬성, Subscriber 반환을 검증했다.
- 회의별/전체 pending 포화, Executor 포화 후 복구, 명령 예외 후 진행, shutdown 거절, 큐 정리를 검증했다.
- 동시 생산자 4명이 400개를 제출하고 idle 큐 생성/정리 100회를 추가하는 시나리오를 5회 반복했다.
- 기존 Registry의 emitter 단독 실패 정리, 전사/녹음 payload, 연결·종료·권한 테스트를 유지했다.
- 기존 테스트의 생성자는 MeterRegistry/Dispatcher 주입에 맞춰 변경하고 DisplayName·목적·Given/When/Then을 보완했다.
- 수정한 Subscriber 검증에서 JSON `null`이 Java null이 아닌 NullNode로 역직렬화되는 기대값을 바로잡았다.

독립 MySQL/Redis로 모든 opt-in 통합 테스트까지 활성화한 전체 결과:
**Total 1,073 / Passed 1,071 / Failures 2 / Errors 0 / Skipped 0.**

실패 2건은 SSE 변경이 전혀 없는 `origin/dev` 원본 worktree에서도 같은 MySQL 환경으로 재현했다.

1. `RecordingMySqlIntegrationTest.startCommitsSessionCreditLedgerAndMeetingTogether`: 원장 amount 기대값 +20, 실제 -20.
2. `RecordingMySqlIntegrationTest.twoMeetingsInSameTeamCannotStartTogether`: `team_credits ... for update` 경쟁의 MySQL deadlock.

이 파일과 녹음/크레딧 로직은 수정하지 않았다. 전체 성공으로 보고하지 않으며 별도 수정 대상이다.
기존 #204 DB·Mock AI는 사용하지 않았다. 환경 변수를 지정하지 않은 일반 실행에서는 기존 opt-in MySQL 테스트가 스킵된다.

실제 Redis 재현 테스트만 실행하려면 전용 Redis 포트로 다음을 실행한다.

```sh
MEETING_SSE_TEST_REDIS_PORT=6379 ./gradlew test \
  --tests 'com.backend.meety.domain.meeting.realtime.MeetingSseRedisIsolationIntegrationTest'
```

## 남은 한계

- 같은 회의의 느린 emitter는 여전히 그 회의의 참가자와 다음 이벤트를 지연시킨다.
- 모든 worker가 느린 회의로 점유되면 다른 회의도 기다린다. worker 수는 부하 테스트로 결정해야 한다.
- Notification Subscriber 자체는 여전히 동기 send를 한다. 느린 Meeting SSE의 영향은 차단했지만 느린 Notification이 공유 Redis 수신 경로를 막을 가능성은 남는다. 별도 Notification Executor는 추가하지 않았다.
- Redis Pub/Sub과 메모리 큐는 best-effort이며 단절·포화·프로세스 종료 시 이벤트 유실이 가능하다.
- 전역 ordering, 자동 replay, 전사 최종 도착을 기다리는 회의 종료 정책은 새로 구현하지 않았다.
- Virtual Thread fan-out, per-user queue, 외부 MQ, Redis routing, WebFlux 전환은 적용하지 않았다.
