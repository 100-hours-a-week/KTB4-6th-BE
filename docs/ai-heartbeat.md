# #320 BE–AI Heartbeat

최초 기준: #319 병합 후 `origin/dev` `a60d4064b861dcae3ae19b5131a00f970fb5f747`.
PR 준비 시 최신 dev `4d7c85c35da3448b37edb49dc579d3523a3ea91c`(#326)을 병합했다.
브랜치: `feat/#320-ai-heartbeat`.

## 목적과 ACK의 차이

- Audio ACK(#319): 실제 전송한 binary Audio의 수신 확인 순번이 전진하는지 감시한다.
- Heartbeat(#320): Audio가 없는 무음·일시정지 중에도 AI WebSocket 서비스가 응답 가능한지 확인한다.

Heartbeat는 ACK sequence, `lastAckProgressAt`, Redis checkpoint, FE cumulative ACK를 변경하지 않는다.
정상 pong이 와도 Audio ACK 진행이 멈추면 `AUDIO_ACK_TIMEOUT`이 발생할 수 있다.
정상 Audio ACK가 와도 outstanding pong이 만료되면 Heartbeat 실패로 계산한다.

## 메시지 계약

BE → AI:

```json
{"type":"heartbeat.ping","requestId":"hb-<UUID>","recordingSessionId":"123"}
```

AI → BE:

```json
{"type":"heartbeat.pong","requestId":"hb-<동일 UUID>","recordingSessionId":"123"}
```

pong은 STT 처리 완료나 PCM 수신 확인이 아니다. AI handler/service의 응답 가능 여부만 확인한다.
session ID, nonblank 문자열 requestId, 현재 registry의 connection 객체, outstanding requestId를 검증한다.
기존 AI inbound 관례와 호환되도록 session ID는 동일 값의 정수 JSON도 수용한다.
duplicate/stale/unknown/mismatched/expired/old-connection pong은 실패 횟수를 초기화하지 않는다.

## 상태와 Scheduler

기존 `AiLiveMeetingConnectionRegistry`를 사용하는 `AiHeartbeatService`의 전역 `@Scheduled` scan이다.
#319와 같은 기존 Spring scheduling 자원을 사용하며, 새 thread pool·registry·connection별 task/future를 만들지 않는다.
STOP timeout 전용 executor에는 반복적인 network write를 추가하지 않는다.

connection의 기존 `sendLock` 안에서 다음 상태를 관리한다:

- `lastPingRequestId`: 현재 outstanding ping. 응답/만료/종료 시 null.
- `lastPingSentAt`: 마지막 성공한 ping write 완료 시각. 응답 후에도 다음 ping 주기 계산에 사용한다.
- `lastPongReceivedAt`: 마지막 정상 pong 시각.
- `consecutiveHeartbeatFailures`: 연속 pong timeout 횟수.
- `heartbeatTimeoutReported`: 동일 실패 구간의 threshold event 중복 방지.
- `heartbeatStopped`: 종료 후 늦은 제어 응답이 와도 Heartbeat를 재시작하지 않는 terminal fence.

READY / PAUSED에서 다음 scan 시 첫 ping을 보낸다. outstanding은 최대 하나다.
응답을 받아도 마지막 성공한 ping write로부터 interval이 지나기 전에는 새 ping을 보내지 않는다.
PAUSE_SENT / RESUME_SENT에서는 새 ping을 보내지 않되 이미 보낸 ping의 pong/timeout은 처리한다.
CONNECTING / START_SENT / STOP_SENT / ENDED / CLOSED에는 새 ping을 보내지 않는다.
PAUSED는 무음과 동일하게 ping/pong을 유지하며 ACK 상태와 관계없다.

## Timeout 정책

```text
outstanding ping 존재
AND now >= lastPingSentAt + pongTimeout
→ outstanding 제거
→ consecutiveHeartbeatFailures += 1
```

동일 scan에서 interval도 지났고 READY/PAUSED이면 다음 ping을 즉시 보낸다.
interval이 timeout보다 길면 다음 interval까지 기다린다. interval이 더 짧아도 outstanding을 덮어쓰지 않는다.
실제 검사 cadence는 interval이고 fixed-delay이므로 timeout 설정값은 엄격한 실행 deadline이 아니다.

기본값에서 첫 ping 이후 약 5초에 failure 1, 다음 ping도 만료되면 약 10초에 failure 2다.
threshold 이상이 되는 최초 1회에만 `AI_HEARTBEAT_TIMEOUT`을 발행하고, 이후에도 probe는 유지한다.
정상 pong은 failures=0 및 event latch를 초기화한다. 다음 장애 구간은 다시 감지할 수 있다.

deadline에 도달한 pong은 scheduler 실행 전이라도 expired로 거절한다. 실패 집계는 다음 scan에서 한 번만 한다.
write 실패는 성공한 ping/outstanding으로 기록하지 않으며 별도 send failure 로그·metric으로 남긴다.
write 실패 자체를 pong timeout으로 세거나 재연결/Fatal로 바꾸지는 않는다.

## 설정

| Property | 기본값 | 환경 변수 |
|---|---|---|
| `ai.heartbeat.interval` | `5s` | `AI_HEARTBEAT_INTERVAL` |
| `ai.heartbeat.pong-timeout` | `5s` | `AI_HEARTBEAT_PONG_TIMEOUT` |
| `ai.heartbeat.failure-threshold` | `2` | `AI_HEARTBEAT_FAILURE_THRESHOLD` |

모두 양수로 검증한다. 운영 초기값이며 실제 AI 통합/부하 테스트 이후 조정한다.

## 동시성과 Cleanup

send / pong / timeout / 종료는 기존 `sendLock`으로 직렬화하며 추가 전송 락은 없다.
pong의 판정 시각은 락 획득 후 읽으므로 검사와 정상화가 동시에 다른 실패 횟수를 덮어쓰지 않는다.
현재 registry connection 검증도 해당 락 안에서 수행해 이전 connection이 새 connection 상태를 변경하지 않는다.

STOP / markStopSent / ENDED / close에서 Heartbeat outstanding과 시각·실패 상태를 비운다.
기존 AI cleanup(#319 ACK cleanup → close → socket close → registry remove)과 registry.remove → close도 재사용한다.
cleanup이 진행 중 write를 기다릴 수는 있지만 cleanup 완료 뒤에는 새 ping을 보내지 않는다.
per-session task/future가 없으므로 취소 누락이나 scheduler closure가 제거된 connection을 영구 참조하지 않는다.
scan의 snapshot 참조는 해당 scan이 끝나면 해제된다. I/O가 block되면 scan 자체도 지연될 수 있다.

현재 Recording COMPLETED는 기존 stop/cleanup 경로로 정리된다. FAILED 전환은 이번 이슈에서 추가하지 않으며
향후 #317의 실제 실패 경로도 기존 closeConnection/cleanup을 호출해야 한다.

## 이벤트 / #321 연결

`AiHeartbeatTimeoutEvent`:

- `reason() = AI_HEARTBEAT_TIMEOUT`
- `recordingSessionId`, `sessionStartRequestId` (connection generation)
- `pingRequestId`, `consecutiveFailures`, `detectedAt`

#319 `AiAudioAckTimeoutEvent`와 같이 관측 snapshot을 락 밖에서 발행한다.
#321 consumer는 처리 시 registry 세대·현재 실패/응답 상태를 다시 확인해야 한다.
검사 후 새 pong이나 cleanup이 먼저 완료될 수 있기 때문이다.
연결 종료, Recording FAILED, FE 종료, 재연결, replay, Fatal Failure는 하지 않는다.
connection 처리와 event consumer의 예외는 scan에서 격리해 다른 연결 검사를 중단하지 않는다.
이벤트 전달 실패의 영속 재시도는 구현하지 않는다.

## 관측

| Metric | 의미 |
|---|---|
| `ai.heartbeat.pings` | 성공한 ping write 수 |
| `ai.heartbeat.pongs` | 유효한 pong 수 |
| `ai.heartbeat.pongs.invalid` | malformed / stale / expired / old connection 등 거절 수 |
| `ai.heartbeat.timeouts` | 만료된 개별 ping 수 |
| `ai.heartbeat.threshold.reached` | 연속 실패 기준 도달 구간 수 |
| `ai.heartbeat.send.failures` | ping write 실패 수 |

sessionId/requestId는 metric label로 사용하지 않는다. ping/pong은 DEBUG, 거절·timeout·전송 실패는 WARN이다.
pong latency timer/histogram은 이번에 추가하지 않았다.

## 검증과 한계

테스트는 정상/오류 계약, PAUSED, 1·2회 실패 및 재정상화, 단일 outstanding, lifecycle,
pong/timeout·cleanup/write·세대 교체 경쟁, Audio ACK와 양방향 상태 독립성을 검증한다.
Spring scheduler 통합 테스트는 실제 `@Scheduled` 등록과 별도 스레드의 inbound 응답 경로를 검증하지만
실제 AI 서버 또는 실제 네트워크 ping/pong 통합 테스트는 아니다.

- 공유 scheduler의 다른 작업, network write, 기존 sendLock 대기로 탐지가 지연될 수 있다.
- write/Redis/FE send 자체에 deadline을 추가한 것이 아니다.
- 제어 전환이 끝나지 않는 연결은 신규 probe 대상이 아니므로 제어 명령 자체의 timeout은 별도 lifecycle 과제다.
- pong은 Audio/STT 파이프라인의 정상 진행을 보장하지 않는다. ACK 감시가 별도로 필요하다.
- 애플리케이션 프로토콜이므로 AI #102가 같은 requestId와 sessionId를 echo해야 한다.
  실제 AI에서 READY·PAUSED·무음·부하·응답 지연 및 서비스 중단을 양 서버로 검증해야 한다.
- #321에서 두 timeout 이벤트를 Recoverable Failure로 소비하고 재연결 정책을 연결한다.
- 최신 dev의 PCM 전송과 decoder reset 제거 결과를 그대로 유지한다.

## 최신 dev / #326 병합 검토

Git의 텍스트 병합 충돌은 없었지만, #320의 `RESET_SENT` 참조가 최신 dev에서 제거된 상태를 사용해
자동 병합 직후 컴파일이 실패했다. Heartbeat 허용 상태 및 테스트의 오래된 constructor/epoch 인자를
최신 API에 맞췄으며 제거된 RESET_SENT 테스트 입력도 제거했다. decoder나 streamEpoch를 복원하지 않았다.

#326의 충돌 해결 commit `73e19f8`과 dev squash 결과를 대조했다.
`AiAudioAckService`, Audio WebSocket handler/handshake/context, PCM enum, Redis checkpoint 및
기존 ACK 테스트는 최신 dev와 동일하게 보존했다. 공유 connection 파일에서도 Audio 전송/ACK 판정은
바꾸지 않고 Heartbeat 상태·메서드와 종료 시 정리만 추가했다.
실제 AI 서버의 PCM·ACK·Heartbeat 계약 전체를 검증했다는 의미는 아니다.

## 최종 테스트 결과 (2026-10-20)

전용 MySQL 8.4 / Redis 7 컨테이너에서 opt-in 테스트를 모두 활성화했다.
기존 #204 DB·Mock AI 환경은 사용하거나 초기화하지 않았다.

| 구분 | Total | Passed | Failures | Errors | Skipped |
|---|---:|---:|---:|---:|---:|
| #320 신규 (상태/경쟁 40 + 설정 5 + Spring scheduler 통합 1) | 46 | 46 | 0 | 0 | 0 |
| #319 ACK 회귀 (실제 Redis 4 포함) | 40 | 40 | 0 | 0 | 0 |
| 관련 AI realtime / Audio WebSocket / Recording owner | 223 | 223 | 0 | 0 | 0 |
| 전체 | 1,178 | 1,176 | 2 | 0 | 0 |
| 깨끗한 최신 dev 전체 | 1,132 | 1,130 | 2 | 0 | 0 |

위 실행들은 서로 포함되므로 합산하지 않는다.
전체 suite는 기존 실패 2건으로 실패 상태다. 훈의 변경을 포함하는 깨끗한 최신 dev `4d7c85c`에서도
전체 테스트를 실행해 동일한 두 실패만 재현했으며, #320 추가 46건 외 신규 실패는 없었다:

1. `RecordingMySqlIntegrationTest.startCommitsSessionCreditLedgerAndMeetingTogether`: 원장 amount 기대 +20, 실제 -20.
2. `RecordingMySqlIntegrationTest.twoMeetingsInSameTeamCannotStartTogether`: `team_credits ... FOR UPDATE` MySQL deadlock.

해당 녹음·크레딧 코드와 기존 실패 테스트는 수정하지 않았다. 실행한 범위에서 #320 신규 regression은 확인되지 않았다.
기존 `AiLiveMeetingConnectionServiceTest`는 Heartbeat 의존성 추가에 맞춘 생성자만 조정했다.
