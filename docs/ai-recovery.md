# #321 Owner-local AI Recovery

구현 시작 기준 `origin/dev`: `7a19e319a52f9ed7e7258ef600ef36610fe3831e` (#319 / #320 병합 후).
최신 dev 병합 기준: `8d34350b37a5dba3dfaeffdff923b708dc61be3f` (#335 전사 저장 비동기화 포함).
브랜치: `feat/#321-ai-recovery`. 원격 브랜치 반영과 PR 생성은 별도 절차로 진행한다.

## 기존 구조에서 확인한 것

- FE Audio frame의 첫 8 bytes는 FE sequence이며 기존 테스트/복구 상태는 첫 청크 1, checkpoint 없음 0이다.
- 기존 AI `audio.meta`는 독립적인 0-based generator다. FE sequence와 처음부터 같은 값이 아니다.
- #319는 connection 내부 pending 대응으로 AI 순번을 FE 순번으로 변환한 뒤 Redis checkpoint를 저장한다.
- Redis `lastProcessedSequence`는 FE sequence이며 AI ACK가 확인된 후에만 저장된다.
- Transcript source key는 `recordingSessionId:sequenceNumber`이고 DB unique constraint가 있다.
- 기존 Transcript 조회는 meeting 내 sequence ASC이며 테스트 데이터의 첫 전사 순번은 1이다.
- #320는 기존 connection/sendLock/registry를 이용하며 READY·PAUSED에서 Heartbeat를 처리한다.

기존 #319의 ACK 검증·Lua checkpoint·FE cumulative ACK와 #320의 Ping/Pong 판정은 중복 구현하지 않았다.

## 상태 및 진입

`AiRecoveryRegistry`의 `ConcurrentHashMap<recordingSessionId, Context>`에만 기술 상태를 둔다.
Map에 유효한 Context가 있으면 RECOVERING이며, DB status에 RECOVERING을 추가하지 않는다.

Context에는 세션/회의/Owner, generation UUID, 최초 원인과 시작 시각, attempt,
현재 connection, fromSequence, deadline, timer future, 진행 증거와 사용자 PAUSE 의도를 보관한다.
connection을 교체해도 같은 Context와 attempt를 유지한다.

다음 신호를 `AiRecoveryService`로 모은다.

- `AiAudioAckTimeoutEvent` → `AUDIO_ACK_TIMEOUT`
- `AiHeartbeatTimeoutEvent` → `AI_HEARTBEAT_TIMEOUT`
- unexpected close / transport error / 연결 I/O로 인한 Audio forward 실패
- Recovery 연결의 시작/프로토콜 실패

현재 registry의 connection과 session.start requestId를 확인한다. Owner 없는 초기 handshake 실패는
기존 시작 실패 정리 경로를 유지한다. STOP/ENDED/closed/fenced connection은 새 Recovery를 시작하지 않는다.

최초 진입은 `putIfAbsent`로 한 번만 성공한다. 이전 connection은 즉시 fence 처리한다.
기존 connection에서 함께 온 timeout/close/error는 중복 시작 또는 attempt 실패로 세지 않는다.
반면 새 attempt connection에서 재발한 장애는 해당 attempt 실패로 처리한다.
timeout event 발행 이후 ACK/pong이 정상 진행했다면 snapshot을 재검증해 오래된 신호를 버린다.

## attempt 흐름

1. bounded 공용 Recovery worker에서 새 attempt를 시작하며 attempt를 증가시킨다.
2. attempt deadline과 취소 가능한 1회 timer를 등록한다.
3. 기존 Redis Owner가 Context Owner와 같은지, DB가 RECORDING/PAUSED인지 확인한다.
4. 기존 connection/ACK/Heartbeat runtime을 정리한다. 정상 종료가 아니므로 Owner/checkpoint는 삭제하지 않는다.
5. Redis checkpoint와 DB 마지막 전사 순번을 다시 읽는다.
6. 새 connection을 reserve하고 기존 `session.start` DTO를 확장해 전송한다.
7. `session.ready` 후 FE replay 요청과 기존 Heartbeat probe를 시작한다.
8. 정상화 조건을 확인하고 성공하거나 다음 attempt / exhausted로 연결한다.

old connection을 닫는 동안 Owner STOP 명령이 사라지지 않도록 CLOSED placeholder를 registry에 잠시 유지하고
새 reserve로 교체한다. placeholder는 정상 connection으로 사용하지 않는다.
DB가 이미 COMPLETED이면 정상 STOP 경로, FAILED 등 비활성 상태이면 cleanup으로 취소한다.

## Sequence 정책

```text
Redis FE checkpoint = 99
→ startSequence = 100
→ 새 AI generator = 100
→ FE replay fromSequence = 100
```

Recovery connection에서만 AI와 FE 시작점을 맞춘다. 기존 정상 연결의 0-based AI generator는 유지한다.
`startSequence` 이전 값은 송신/ACK baseline일 뿐 pending으로 계산하지 않는다.
replay 요청 전 전송은 허용하지 않고, 정상화 검증 중에는 fromSequence부터 연속 FE 청크만 전송한다.
그 결과 마지막 ACK 109가 실제 100..109의 10청크 수신 확인이라는 의미를 가진다.
성공 후에는 검증용 연속 제한을 해제해 기존 일반 Audio gap 처리 계약을 유지한다.

`transcriptSequenceStart = max(DB meeting 내 최대 sequence, 이전 connection의 큐 수락 최대 순번) + 1`이다.
전사가 없으면 기존 테스트의 첫 순번 1을 사용한다.
soft-delete된 행도 source key를 계속 점유하므로 max 조회에서 제외하지 않는다.
새 sequence 저장소나 DB column은 추가하지 않는다.

최신 dev #335의 TranscriptSaveDispatcher 및 저장 재시도/종료 로직은 변경하지 않았다.
#321은 기존 sendLock 안에서 현재 연결의 큐 제출과 수락된 최대 순번만 기록한다.
DB 저장을 기다리지 않으므로 AI 수신 스레드를 다시 DB I/O에 묶지 않는다.
retire 이후 DB max와 큐 수락 최대값을 함께 확인해 아직 commit되지 않은 전사의 순번을 재사용하지 않는다.
새 connection도 이 순번 하한을 이어받아 두 번째 attempt에서도 유지한다. fence 뒤의 늦은 전사는 enqueue하지 않는다.
큐 제출 거절은 순번 하한을 올리지 않으며, 기존 Dispatcher의 best-effort 유실 정책은 그대로다.

기존 `AiSessionStartMessage`에 nullable `startSequence`와 `transcriptSequenceStart`를 추가했다.
Recovery가 아닌 일반 session.start에서는 두 필드를 생략해 기존 wire contract를 유지한다.

## FE 메시지

기존 Audio WebSocket registry에 등록된 녹음 시작자의 소켓으로 전송한다.

```json
{"type":"recording.recovering","recordingSessionId":123,"code":"AI_CONNECTION_RECOVERING"}
```

새 `session.ready` 이후:

```json
{"type":"recording.replay.request","recordingSessionId":123,"fromSequence":100}
```

성공:

```json
{"type":"recording.recovery.completed","recordingSessionId":123}
```

BE가 replay 종료 위치를 정하거나 PCM Replay/Live queue를 만들지 않는다.
FE는 capture/IndexedDB를 유지하고 지정된 순번부터 기존 sequence 흐름을 이어 보내야 한다.
기존 `{type:"ack",seq:N}` 및 10청크 cumulative ACK 기준은 바꾸지 않는다.

## 성공 / 실패

성공에는 모두 필요하다:

```text
새 session.ready 완료 및 replay 요청
AND 새 connection에서 deadline 이내 정상 pong 확인
AND deadline 이내 lastAckedSequence >= fromSequence + successAckChunks - 1
AND 해당 위치까지 Redis checkpoint 저장 확인
AND 현재 connection / Owner / 활성 DB 상태 재검증
```

ACK 메시지 수를 세지 않는다. duplicate/stale/future ACK는 #319에서 걸러진다.
deadline 전의 ACK/pong 증거는 Context에 보관해 worker 대기 중 더 최신 시각이 덮어써도 오탐하지 않는다.
Redis 실패로 FE ACK가 보장되지 않는 상태에서는 Recovery 완료를 알리지 않는다.

진행 증거는 attempt별 immutable connection 식별자와 Atomic 상태로 분리한다. native callback은
Context lock을 잡지 않고 증거만 기록한 뒤 worker로 넘겨 sendLock/Context lock 역전을 피한다.
동시에 들어온 실패 원인은 ACK/Heartbeat/transport별로 합쳐 보관한다. ACK가 회복됐다는 이유로
함께 발생한 아직 유효한 Heartbeat 장애까지 버리지 않는다.

실패하면 timer를 취소하고 현재 attempt를 한 번만 실패시킨다. 다음 attempt도 checkpoint를 재조회한다.
새 WebSocket이 생겨도 attempt를 0으로 초기화하지 않는다. 성공 시 Context를 제거한다.
STOP/cleanup/Owner 변경은 명시적 취소이므로 retry하지 않는다.

## STOP / PAUSE / Owner

- STOP 요청 flag를 Context 취소 전에 세워 초기 failure/STOP 경쟁도 fencing한다.
- 진행 중 connect가 늦게 끝나면 socket을 닫고 session.start를 보내지 않는다.
- PAUSE/RESUME은 Recovery 동안 Context의 최신 희망 상태로 보관한다. replay를 위해 임시 READY를 사용한다.
- 성공 직전에 DB 상태와 제어 version을 재검증하고 필요하면 기존 AI PAUSE 명령을 복원한다.
- Recovery가 DB RecordingSession을 RECORDING/PAUSED/FAILED로 변경하지 않는다.
- Context는 Owner BE 메모리에만 있다. 다른 BE/Owner로 변경되면 이전 attempt/generation을 승계하지 않는다.
- 같은 BE에서 재사용된 연결에 새 Owner가 등록되면 이전 Context/timer를 취소하고 기존 FE handshake 복구를 허용한다.
- Redis에는 기존 Owner/checkpoint만 사용한다. 분산 Recovery state/attempt/generation이나 lock을 추가하지 않는다.

## 자원 / deadline / cleanup

- 기존 `AiStopTimeoutScheduler`를 1회 timer 자원으로 재사용한다. timer callback에서 connect를 직접 실행하지 않는다.
- 취소된 timer는 scheduler 큐에서 즉시 제거하도록 remove-on-cancel을 설정했다.
- 재연결은 별도 bounded 공유 executor로 실행한다. 2 workers / queue 64는 부하 검증 전 초기 예산이다.
- 진행 검사는 Context별 enqueue를 coalesce해 ACK마다 무한한 작업을 쌓지 않는다.
- queue 거절은 기존 timer에 종료 처리를 넘겨 Context를 정리하고 exhausted 이벤트를 발생시킨다.
  native callback에서 Context lock을 기다리거나 CallerRuns로 connect를 실행하지 않는다.
- attempt 교체 시 완전히 초기화한 AudioStreamState를 한 번에 게시한다. 이전 stream-state lock을
  잡거나 늦은 attempt worker가 다음 attempt의 ACK 상태를 덮어쓰지 않는다.
- 실제 WebSocket connect 대기는 남은 attempt 시간으로 제한하고, timeout 뒤 완성된 future의 socket은 닫는다.
- exhausted 시 논리적으로 fence/detach한 뒤 물리 cleanup을 worker에 제출한다.
- STOP / cleanup / registry remove / 성공 / exhausted에서 Context 및 timer를 정리한다.
- 취소된 worker 항목은 실행 시 유효성을 확인하고 종료한다. per-session thread나 영구 반복 task는 없다.

5초는 attempt의 논리적인 정상화 기준이다. 초기 executor 큐 대기는 attempt 시작 전이며,
외부 DB/Redis/소켓 write/close 및 기존 sendLock 대기는 엄격한 5초 I/O deadline이 아니다.
특히 기존 FE send나 동기 외부 I/O가 오래 걸리면 검사/제어가 지연될 수 있다.
worker가 완전히 포화/종료돼 물리 cleanup 제출도 거절되면 로그가 남고 물리 자원 정리가 지연될 수 있다.
이 상태의 DB/FE 종료는 #317 consumer가 처리한다. 외부 I/O 자체가 block되는 경우의 자원 정리 지연은 남는다.

## #317 진입점과 관측

`AiRecoveryExhaustedEvent`에 세션 ID, Recovery generation, Owner token, 마지막 session.start requestId,
attempt 수와 최초 장애 원인을 담는다. #317의 RealtimeFatalFailureService가 최신 Owner/세대를 재검증하고
Recording FAILED를 확정한 뒤 AFTER_COMMIT에서 runtime/FE/Owner를 정리하고 참가자 SSE를 전파한다.
추가 확정 정책으로 Fatal 시 Meeting COMPLETED와 실제 녹음 비용 전액 보상을 같은 transaction에서 처리한다.
정상 녹음 완료/전사 확정 이벤트는 발행하지 않아 자동 요약은 시작하지 않는다. 상세 내용은 `realtime-fatal-failure.md`를 참고한다.

Micrometer counter (sessionId/generation/requestId tag 없음):

- `ai.recovery.started`
- `ai.recovery.attempts`
- `ai.recovery.success`
- `ai.recovery.failure`
- `ai.recovery.timeouts`
- `ai.recovery.exhausted`

개별 세션/세대/attempt/원인은 로그로 남긴다.

## 설정

| Property | 기본값 | 환경 변수 |
|---|---|---|
| `ai.recovery.max-attempts` | `2` | `AI_RECOVERY_MAX_ATTEMPTS` |
| `ai.recovery.attempt-timeout` | `5s` | `AI_RECOVERY_ATTEMPT_TIMEOUT` |
| `ai.recovery.success-ack-chunks` | `10` | `AI_RECOVERY_SUCCESS_ACK_CHUNKS` |
| `ai.recovery.workers` | `2` | `AI_RECOVERY_WORKERS` |
| `ai.recovery.queue-capacity` | `64` | `AI_RECOVERY_QUEUE_CAPACITY` |

## 검증 결과 (2026-10-21)

기존 #204 DB나 Mock AI를 재사용하지 않고 이번 검증 전용 MySQL 8.4 / Redis 7을 사용했다.
단위 테스트의 AI WebSocket은 fake이며 실제 AI 서버와의 통합 검증은 별도다.

| 실행 범위 | 전체 | 통과 | 실패 | 오류 | Skip |
|---|---:|---:|---:|---:|---:|
| 신규 #321 (실제 Redis checkpoint 1건 포함) | 41 | 41 | 0 | 0 | 0 |
| #319 ACK / #320 Heartbeat 회귀 | 86 | 86 | 0 | 0 | 0 |
| 최신 dev 병합 후 AI realtime / Audio WebSocket / Owner / Transcript | 363 | 363 | 0 | 0 | 0 |
| 최신 dev 병합 후 전체 | 1,231 | 1,229 | 2 | 0 | 0 |
| 깨끗한 구현 시작 기준 dev (`7a19e31`) 전체 | 1,178 | 1,176 | 2 | 0 | 0 |

두 전체 실행의 실패는 동일한 `RecordingMySqlIntegrationTest` 두 건이다.

- `startCommitsSessionCreditLedgerAndMeetingTogether`: ledger amount 기대값 `20`, 실제 `-20`.
- `twoMeetingsInSameTeamCannotStartTogether`: `team_credits ... FOR UPDATE`의 MySQL deadlock.

관련 없는 크레딧/녹음 로직이나 기존 assertion은 수정하지 않았다. 기존 테스트 네 파일은
추가 의존성 주입 및 current-connection mock만 보완했다.

추가 fetch에서 dev의 #335 병합을 확인해 최신 dev를 통합했다. InboundHandler와 ACK/Heartbeat 테스트
세 파일의 텍스트 충돌을 해결했고 Recovery 재연결 경로의 Dispatcher 의존성을 맞췄다.
비동기 큐 수락 순번을 두 attempt에 걸쳐 보존하는 회귀 테스트로 의미상 충돌도 검증했다.
훈의 Dispatcher/Properties/Executor/StopListener 구현과 테스트는 최신 dev 원본을 유지했다.

## 남은 실제 통합 검증

- AI가 startSequence / transcriptSequenceStart를 적용하고 새 audio.meta 순번을 정확히 echo하는지 확인.
- FE가 새 Recovery 메시지를 지원하고 fromSequence부터 빠짐없이 PCM을 재전송하는지 확인.
- PAUSED 중 재전송 가능한 청크가 10개보다 적으면, 고정 성공 기준상 5초 안에 성공할 수 없어 retry/exhausted될 수 있다.
  이 기준을 임의로 완화하지 않았다. 실제 FE 버퍼 보존/PAUSED replay 정책을 양쪽으로 검증해야 한다.
- 정상 무음, 대규모 buffer replay, Owner BE 변경 및 process 종료 후 새 Owner의 별도 복구를 검증.
- Owner BE 종료 시 메모리의 큐 수락 순번은 승계하지 않는다. 비동기 Dispatcher의 영속 큐/무손실 처리는 이번 범위가 아니다.
- 실제 FE/AI와 #317 Fatal consumer의 최종 오류 UI 및 참가자 상태 동기화 통합 검증.
