# #317 Realtime Fatal Failure

기준 dev: `66766a0152bf3da8e52554ee55e140e22e6d9f43`.
#321은 #336에서 squash 병합됐으며 원본 #321 최종 tree와 동일함을 확인했다.
작업 브랜치: `feat/#317-realtime-fatal-failure`.

## 기존 구조와 재사용

- 정상 STOP은 RecordingService에서 meeting → recording 순서의 PESSIMISTIC_WRITE 잠금을 사용한다.
- RecordingSession.complete는 RECORDING/PAUSED만 허용하고 FAILED는 이미 거절한다.
- 정상 종료는 RecordingCompletedEvent / MeetingCompletedEvent 후처리를 유지한다.
- #321은 Owner-local Context/세대/attempt/timer를 관리하고 소진 시 runtime을 detach한다.
- AI inbound 전사는 기존 TranscriptSaveDispatcher의 녹음별 순서 큐와 bounded retry로 저장한다.
- Owner 해제는 기존 owner-token Lua compare-and-delete로 Owner와 checkpoint를 함께 삭제한다.
- MeetingSsePublisher → `meeting-sse` Redis Pub/Sub → Subscriber → 회의별 Dispatcher → local emitter 경로를 유지한다.

## 분류와 진입점

`RealtimeFatalFailureService`가 유일한 DB FAILED 진입점이다.

- `AiRecoveryExhaustedEvent`: Recovery 최종 실패만 수신한다. 최초 ACK/Heartbeat timeout에는 Fatal listener가 없다.
- `AiFatalFailureEvent`: 현재 소켓의 처리 불가능한 메시지, 명시적인 session identity 불일치, retry 불가 AI error.
- 전사 저장: 기존 retry/상한/backoff/정상 finalize는 유지한다. 최종 실패 또는 queue 제출 거절 시 callback으로 Fatal을 전달한다.
- retry 가능한 AI error는 기존 #321 공통 Recovery 진입점에 위임한다.

시작 전 Owner가 없는 연결은 기존 Start Failure cleanup을 유지한다. 정상 STOP 중 늦은 불일치 제어 응답은 무시한다.
기존 PAUSE/RESUME wire format에 identity 필드를 새로 요구하지 않는다.
ACK/Pong invalid/stale/duplicate 판정은 #319/#320를 유지한다. 알 수 없는 확장 type은 기존처럼 로그만 남긴다.

전사 실패 callback은 enqueue가 sendLock에서 빠져나온 뒤 전달한다. 큐 거절 callback이 동기로 호출돼도
Fatal cleanup이 sendLock → AudioStreamState 순으로 잠금을 역전하지 않도록 완료 신호로 경계를 나눈다.
추가 executor, 전사 큐, Redis runtime state는 만들지 않는다.

## 원인 code

WS/SSE 전용 enum `RealtimeFailureReason`을 사용한다. HTTP ErrorCode/API 계약 변경은 없다.

- AI_PROCESSING_ERROR
- AI_CONNECTION_CLOSED
- AI_TRANSPORT_ERROR
- AI_HEARTBEAT_TIMEOUT
- AUDIO_ACK_TIMEOUT
- AUDIO_FORWARD_FAILED
- TRANSCRIPT_PROCESSING_FAILED
- AI_INBOUND_PROCESSING_FAILED

#321 소진 이벤트는 최종 connect/attempt timeout 대신 Context의 최초 원인을 담도록 보완했다.
`AI_UNEXPECTED_CLOSE`는 `AI_CONNECTION_CLOSED`, `AUDIO_FORWARD_FAILURE`는 `AUDIO_FORWARD_FAILED`로 매핑한다.

## 선점 / STOP / transaction

```text
Fatal signal
→ REQUIRES_NEW transaction
→ meeting 잠금 → recording 잠금
→ terminal 상태 / Redis Owner / 현재 connection 또는 Recovery generation 검증
→ RecordingSession.fail(현재 시각) + Meeting.complete(동일 시각)
→ 팀 크레딧 잠금 → 녹음 차감 전액 복원 + RESTORE 원장
→ RecordingFailedEvent / MeetingCompletedEvent 등록
→ COMMIT
→ AFTER_COMMIT runtime cleanup 및 FE 오류/close
→ AFTER_COMMIT RECORDING_FAILED SSE publish
→ 기존 회의 완료 consumer로 참가자 SSE 종료 및 진행 중 채팅 취소
```

FAILED 전이는 DB 행 잠금 아래 한 번만 성공한다. 정상 STOP과 동일한 잠금 순서이므로
먼저 확정된 COMPLETED/FAILED를 후행 경로가 덮어쓰지 않는다. 기존 정상 STOP 로직은 변경하지 않았다.
Event는 기존 패턴대로 transaction 안에 등록하지만 FE/Owner/SSE consumer는 AFTER_COMMIT만 실행한다.
rollback 시 외부 cleanup/전파를 하지 않는다. runtime 선제 종료로 rollback된 녹음을 깨뜨리지 않기 위해
실제 소켓 종료도 커밋 이후에 수행한다.

### Fatal 회의 종료 / 크레딧 보상

추가 확정 정책에 따라 FAILED·Meeting COMPLETED·녹음 비용 보상을 같은 transaction에 포함한다.
잠금 순서는 기존 meeting → recording 뒤 team_credits를 추가하며, 다른 차감/적립과 같은 잔액 행을 잠근다.

- `USE:RECORDING:{recordingSessionId}` 실제 차감 원장의 음수 금액을 전액 복원한다. 현재 녹음 비용은 20이다.
- 차감 원장이 없다면 임의로 크레딧을 생성하지 않고 종료만 처리한다.
- 보상 원장: `RESTORE:RECORDING:{recordingSessionId}`, type=RESTORE, sourceType=RECORDING,
  sourceId=세션 ID, amount=+20, balanceAfter=보상 후 잔액.
- 기존 원장 멱등키 unique constraint와 팀 잔액 잠금, terminal 전이 선점으로 중복 지급을 막는다.
- 이미 RESTORE 원장이 있으면 추가 지급하지 않는다. 팀 크레딧 행이 없거나 원장이 유효하지 않으면
  상태·회의·보상 전체를 rollback하고 AFTER_COMMIT 전파도 실행하지 않는다.
- 전액 보상은 정기 적립과 달리 maxBalance를 초과하더라도 금액을 줄이지 않는다.
  Summary/Chat의 기존 capped 복원 정책은 변경하지 않는다.
- MeetingCompletedEvent를 재사용해 기존 채팅 취소와 Meeting SSE 종료를 수행한다.
  정상 RecordingCompletedEvent/MeetingTranscriptFinalizedEvent는 발행하지 않아 Fatal이 자동 요약/Metric을 시작하지 않는다.
- 과거 FAILED 세션에 대한 소급 보상/backfill은 하지 않는다.

이전 connection의 네트워크 Fatal은 현재 requestId와 Owner가 일치해야 한다. 소진은 새 Recovery generation이
존재하면 무시한다. 수락된 전사 저장 실패는 connection 교체와 무관한 세션 데이터 무결성 장애이므로
제출 당시 Owner를 보관하고 session-scoped signal로 전달한다. 새 Owner의 실패로 재귀속하지 않는다.

## 커밋 이후 cleanup / 전파

1. 현재 Owner의 connection을 stop/fence한다.
2. 같은 Owner의 Recovery Context를 기존 lock 아래 취소하고 timer를 정리한다.
3. registry를 다시 확인해 교체된 connection도 cleanup한다. 늦은 connect 결과는 attach되지 않는다.
4. 시작자 FE에 recording.error를 시도하고 Audio WebSocket을 close/remove한다.
5. 기존 Lua 조건부 삭제로 Owner/checkpoint를 해제한다.
6. 같은 RecordingFailedEvent의 다음 AFTER_COMMIT listener가 Meeting SSE를 발행한다.
7. MeetingCompletedEvent의 기존 LOWEST_PRECEDENCE listener가 FAILED 이벤트 뒤 Meeting SSE를 종료한다.

각 외부 실패는 로그로 남기고 다음 후처리를 계속한다. FE가 이미 닫혔거나 send가 실패해도 DB FAILED는 유지된다.
Owner 재검증 실패 역시 이미 커밋된 로컬 종료를 취소하지 않는다. 명시적으로 다른 Owner가 확인되면 새 FE를 건드리지 않는다.

FE payload:

```json
{"type":"recording.error","recordingSessionId":123,"code":"AI_CONNECTION_CLOSED","message":"실시간 전사를 계속할 수 없습니다.","action":"STOP_RECORDING"}
```

SSE event name: `RECORDING_FAILED`, payload:

```json
{"event":"RECORDING_FAILED","recordingSessionId":123,"reason":"AI_CONNECTION_CLOSED"}
```

회의가 COMPLETED가 되므로 RECORDING_FAILED 전파 뒤 기존 회의 종료 흐름으로 Meeting SSE를 정리한다.

## 관측

- `recording.fatal.failures{reason}`: 커밋된 실패 (고정 enum 8종만 tag)
- `recording.fatal.ignored`: duplicate / terminal / stale signal 무시
- `recording.fatal.fe.send.failures`
- `recording.fatal.owner.cleanup.failures`

세션/회의 식별자는 로그에만 남긴다. metric tag에 세션/회의/Owner/requestId를 넣지 않는다.

## 의도적으로 남긴 경계

- Fatal은 RecordingSession FAILED·Meeting COMPLETED·녹음 비용 보상을 원자적으로 처리한다.
  정상 종료의 자동 요약/Metric 생성은 여전히 실행하지 않는다.
- DB와 Redis는 하나의 분산 transaction이 아니다. Owner 검증 직후 Owner가 바뀌는 극단적인 경계는
  원자적으로 묶지 않는다. cleanup은 다시 Owner를 확인하고 삭제는 기존 Lua로 새 token을 보존한다.
- DB/Owner 검증 실패 시 FAILED 전이를 확정할 수 없고 외부 종료 전파도 하지 않는다.
  Fatal 자체의 durable 재시도/outbox는 이번 범위에 없다.
- AFTER_COMMIT과 Redis Pub/Sub는 best-effort다. process 종료/외부 장애 시 FE/SSE 전달을 보장하지 않는다.
- Socket I/O가 block되는 경우 후처리 지연은 가능하다. FE의 응답 메시지를 기다리는 프로토콜은 없다.
- #321의 PAUSED 10청크 미만 성공 기준은 변경하지 않았다.

## 검증

전용 MySQL 8.4 / Redis 7을 사용하고 기존 #204/다른 작업 DB는 사용하지 않는다.
신규 테스트는 source 분류, 저장 실패 hook, 실제 행 잠금의 STOP/Fatal 경쟁, 중복 실패,
rollback/AFTER_COMMIT, timer/worker cleanup, FE 실패, 실제 Owner Lua 및 Pub/Sub를 검증한다.

| 범위 | 전체 | 통과 | 실패 | 오류 | Skip |
|---|---:|---:|---:|---:|---:|
| Fatal·회의 종료·보상 선별 (기존 TeamCredit 포함) | 50 | 50 | 0 | 0 | 0 |
| 관련 AI realtime / Recording / Transcript / Credit | 585 | 583 | 2 | 0 | 0 |
| 전체 | 1,276 | 1,274 | 2 | 0 | 0 |
| 깨끗한 최신 dev `66766a0` 전체 | 1,232 | 1,230 | 2 | 0 | 0 |

두 실패는 모두 변경 없는 최신 dev에서도 재현되는 `RecordingMySqlIntegrationTest`다.
`startCommitsSessionCreditLedgerAndMeetingTogether`는 ledger 기대값 +20 / 실제 -20,
`twoMeetingsInSameTeamCannotStartTogether`는 MySQL team_credits 행 잠금 deadlock이다.
관련 없는 도메인이나 기존 실패 assertion은 수정하지 않았다.

기존 AiLiveMeetingConnectionServiceTest 1건은 retry 가능한 AI error의 변경된 위임 정책을 검증하도록
보완했다. Owner를 바로 해제하지 않는 assertion을 유지하고 Recovery event를 검증한다.

추가 정책 테스트 8건은 보상 원장 계약, 적립 상한에서의 전액 지급, 이미 보상된 원장의 멱등성,
차감 원장 부재, 보상 실패의 전체 rollback, 다른 차감과의 잔액 경쟁, 정상 STOP 후 보상 금지를 검증한다.
기존 중복 Fatal/STOP 경쟁/rollback 테스트도 Meeting 상태·잔액·원장·SSE 종료 순서까지 보완했다.

최종 `git fetch origin` 후에도 dev는 `66766a0`으로 기준과 동일하다. 추가 dev diff가 없어
현재 텍스트/의미상 병합 충돌은 없으며 ACK/Heartbeat/Recovery/Owner/Transcript/SSE 회귀를 확인했다.
`git diff --check` 통과. commit/push/PR은 하지 않았다.

## 변경 파일

| 파일 | 역할 |
|---|---|
| `RealtimeFatalFailureService` | 현재 source 검증, 기존 DB 잠금, FAILED 선점 및 event 등록 |
| `RealtimeFatalFailureListener` | AFTER_COMMIT Recovery/AI/FE/Owner cleanup |
| `AiFatalFailureEvent`, `RecordingFailedEvent` | 즉시 Fatal 입력 및 커밋 연계 domain event |
| `RealtimeFailureReason`, `RecordingErrorMessage`, `RecordingFailedSseEvent` | 고정 실패 code 및 WS/SSE payload |
| `RecordingSession` | 기존 FAILED 상태로 전이하는 도메인 메서드 |
| `TeamCredit`, `CreditLedger`, `CreditLedgerRepository` | 실제 녹음 차감 조회, 상한 없는 전액 복원, 기존 RESTORE 원장 패턴 |
| `RecordingSseEventListener` | 기존 Redis publisher로 RECORDING_FAILED 전파 |
| `AiLiveMeetingInboundHandler`, `AiLiveMeetingConnectionService` | source 분류 및 owner 범위 runtime cleanup |
| `AiRecoveryRegistry`, `AiRecoveryService` | 기존 generation 조회와 최초 소진 원인 보존 |
| `TranscriptSaveDispatcher` | 기존 retry를 유지한 최종 실패 callback 추가 |
| `AiFatalFailureRoutingTest` | 즉시 Fatal/Recovery 분류, late callback 및 timer/worker cleanup |
| `RealtimeFatalFailureMySqlIntegrationTest` | DB·STOP 경쟁, 멱등성, rollback 및 커밋 이후 전파 |
| `RealtimeFatalRedisIntegrationTest` | 실제 Owner Lua 및 meeting-sse PubSub |
| `RealtimeFatalEntityTest` | 활성/PAUSED 실패 전이 및 후행 complete 방어 |
| `TranscriptFatalFailureTest` | retry 성공/소진, non-retryable 실패 및 queue 거절 |
| `TeamCreditTest`, `CreditLedgerRecordingRestoreTest` | 전액 보상과 RESTORE:RECORDING 원장 계약 |
| `AiLiveMeetingConnectionServiceTest` | retry 가능한 AI 오류의 Recovery 위임 회귀 보완 |
| `docs/ai-recovery.md`, 이 문서 | #317 consumer 연결 및 설계/검증/한계 기록 |
