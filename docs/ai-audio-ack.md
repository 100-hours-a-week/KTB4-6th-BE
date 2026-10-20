# #319 AI Audio ACK

기준 `origin/dev`: `761ac04`. 작업 브랜치: `feat/#319-ai-audio-ack`.
기존 Working Tree는 깨끗했으며, 이 작업은 커밋/푸시하지 않는다.

## 기존 구조와 변경 경계

`AudioWebSocketHandler`는 FE의 8-byte sequence 헤더를 분리한다.
`AiLiveMeetingConnection`은 별도의 0-based `audio.meta.payload.sequence`를 생성하고 binary를 전송한다.
기존에는 이 write 성공 직후 FE 순번을 Redis에 기록하고 10청크마다 FE cumulative ACK를 보냈다.
전송 성공과 AI 수신 확인은 다르므로 이 checkpoint/ACK 경로를 AI 응답 이후로 옮겼다.

새 경로:

```text
FE frame → 기존 전송 경로 → AI audio.meta + binary
                              ↓
AI audio.ack → InboundHandler → AiAudioAckService
                              ↓
RecordingSession/순번/현재 연결 검증
→ lastAckedSequence 및 progress 시각 갱신
→ Redis 원자적 checkpoint
→ 기존 FE 누적 ACK(10청크, {type:"ack",seq:...})
```

AI ACK는 WebSocket 수신 확인이며 STT 완료나 영구 저장 보장이 아니다.
AI ACK 순번과 FE 순번은 다른 공간이다. 연결 내부 bounded TreeMap으로 대응한다.
새 전역 Map/Registry는 만들지 않았다. 기존 FE Registry/AudioStreamState를 재사용한다.

## 상태와 동시성

- `nextAudioSequence`: 기존 AtomicLong audioSequence의 next-to-send 역할. 0부터 예약하며 부분 write 실패에도 재사용하지 않는다.
- `lastSentSequence`: metadata와 binary write가 모두 성공한 뒤 확정. 초기값 -1.
- `lastAckedSequence`: 유효한 AI ACK만 전진시킴. 초기값 -1. 미래/미전송/중복/과거 ACK로 전진하거나 역행하지 않는다.
- `lastAckReceivedAt`: 마지막 유효한 ACK 진행 시각.
- `lastAckProgressAt`: 첫 pending 발생 또는 더 큰 ACK 진행 시각. 전부 ACK되면 null.
- 모두 기존 `sendLock`으로 보호하는 일반 필드다. 중복 AtomicLong이나 추가 전송 락을 두지 않았다.

락 순서는 FE `AudioStreamState` → AI `sendLock` → 필요 시 FE socket이다.
전송과 ACK, cleanup을 같은 순서로 직렬화한다. FE socket write도 서로 직렬화한다.
단, 기존 네트워크/Redis write가 오래 걸리면 이 경계도 대기할 수 있다. 이 기능은 I/O 자체의 deadline이 아니다.

현재 AI Registry의 연결 객체 및 session.start requestId가 일치해야 응답을 반영한다.
Redis는 기존 Owner 토큰이 설정된 연결이라면 같은 Owner일 때만 checkpoint를 기록한다.
Lua에서 기존 순번보다 큰 값/같은 값만 기록해 멀티 writer의 checkpoint 역행을 막는다.
BIGINT 정밀도를 보존하기 위해 Lua의 부동소수점 tonumber 비교 대신 양수 문자열 길이/사전순을 비교한다.

Redis 실패 시 FE ACK/로컬 checkpoint를 올리지 않는다. AI 확인 상태는 유지하고 대응 정보도 남긴다.
같은 ACK 재수신 또는 다음 진행 ACK에서 checkpoint를 다시 시도할 수 있으나, 독립 재시도/Recovery 작업은 추가하지 않았다.

## Timeout

```text
lastSentSequence > lastAckedSequence
AND lastAckProgressAt != null
AND now >= lastAckProgressAt + timeout
```

- pending이 없는 무음에는 발생하지 않는다.
- 긴 무음 후 새 PCM을 보내면 새 pending의 전송 완료 시점부터 계산한다.
- 더 큰 ACK만 timer를 갱신한다. duplicate/stale ACK나 추가 send는 기존 pending의 deadline을 미루지 않는다.
- PAUSED + pending 없음은 정상이다. PAUSE 전에 보낸 미확인 청크가 남아 있다면 timeout을 계속 감시한다.
- pending 해소 후 progress timer는 null로 초기화된다.
- 같은 미진행 구간에서는 한 번만 감지하고, 새 ACK 진행 후 다시 감시한다.

`AiAudioAckTimeoutEvent`에 `AUDIO_ACK_TIMEOUT`, recordingSessionId, session.start requestId,
감지 시점 sent/acked snapshot을 담는다. 로그/metric/event만 발생하며 회의 종료, FAILED 전환,
FE 종료, AI 재연결은 하지 않는다. #321의 event consumer는 연결 세대와 현재 진행 상태를 다시 검증해야 한다.

기존 Spring 스케줄러에서 검사한다. 5초는 판정 threshold이며 공유 스케줄러/락 대기에 따른 관측 지연이 가능하다.

## 설정과 메모리 상한

| Property | 기본값 | 환경 변수 |
|---|---|---|
| `ai.audio-ack.timeout` | `5s` | `AI_AUDIO_ACK_TIMEOUT` |
| `ai.audio-ack.poll-delay` | `1s` | `AI_AUDIO_ACK_POLL_DELAY` |
| `ai.audio-ack.max-pending-chunks` | `1024` | `AI_AUDIO_ACK_MAX_PENDING_CHUNKS` |

1024는 부하 검증된 값이 아닌 연결별 초기 메모리 예산이다.
상한/부분 write 실패로 전송하지 못한 FE 청크를 이후 cumulative ACK로 건너뛰지 않도록 해당 순번 재전송 전까지
더 높은 FE 청크를 거절한다. 자동 replay/reconnect는 하지 않는다. 버퍼는 오디오 자체가 아니라 순번 대응만 보관한다.

STOP 전송, connection cleanup, 현재 connection Registry 제거 시 ACK 대응 정보와 progress 상태를 정리한다.
FE stream state는 기존 종료 경로/AI cleanup에서 정리한다. 이미 정리된 state를 늦은 ACK나 close 로그가 다시 생성하지 않는다.
현재 RecordingSession의 FAILED 전환을 새로 만들지는 않는다. 향후 #317의 실제 실패 경로에서도 기존 closeConnection/cleanup을 호출해야 한다.

## 관측

- `ai.audio.ack.received`: 수신 수
- `ai.audio.ack.outcomes{result}`: advanced / duplicate / stale / invalid / closed
- `ai.audio.ack.invalid`: 유효하지 않은 ACK
- `ai.audio.ack.checkpoint.failures`: Redis 실패 또는 Owner/순번 조건 거절
- `ai.audio.ack.pending.rejected`: pending 상한 거절
- `ai.audio.ack.timeouts`: 미진행 구간 timeout 수
- `ai.audio.ack.lag`: 현재 연결의 `lastSentSequence - lastAckedSequence` 합계

sessionId/sequence/requestId는 개별 로그에만 기록하며 metric label로 사용하지 않는다.
ACK 진행은 DEBUG, 비정상 순번/저장 실패/timeout은 WARN으로 기록한다.

## 검증 및 남은 범위

신규 단위/설정/Redis 테스트는 AI ACK 순서, checkpoint-before-FE ACK, timeout 시각,
무음·PAUSED pending, 전송/ACK/cleanup 경쟁, Owner/역순 Redis 쓰기와 상한을 검증한다.
기존 Audio handler 테스트의 send-success 기대를 AI ACK 이후로 변경하고 wire 형식/배치/복구 계약 검증은 유지했다.

전용 MySQL/Redis에서 모든 opt-in 테스트를 활성화했다. 기존 #204 환경은 사용하지 않았다.
신규 테스트 40건(ACK 상태/동시성 32, 설정 4, 실제 Redis 4), 관련 도메인 187건은 모두 통과했다.
최종 전체 결과는 Total 1,142 / Passed 1,140 / Failures 2 / Errors 0 / Skipped 0이다.
전체 테스트의 녹음 실패 2건은 깨끗한 기준 dev에서도 재현했다:
원장 amount 기대 +20/실제 -20, 두 회의 동시 시작의 MySQL deadlock. 관련 없는 녹음·크레딧 코드는 변경하지 않는다.

- #320: Ping/Pong/heartbeat 생존 확인.
- #321: timeout event 이후 복구, checkpoint 기반 재연결, PCM replay, 중복 전사 방어.
- #317: 실제 Fatal Failure 및 FAILED lifecycle 연결.
- AI #102: 실제 PCM과 ACK echo 순번, ACK 진행 순서/누적 수신 의미, 처리 지연을 양쪽 서버로 통합 검증해야 한다.
  현재 dev에는 WEBM_OPUS/MP4_AAC 및 decoder reset 코드가 남아 있다. 이 이슈에서는 포맷/decoder 대규모 변경을 하지 않았고
  ACK 계층은 전송한 binary의 형식 변환을 수행하지 않는다. 실제 PCM 프로토콜 정합성까지 검증했다고 주장하지 않는다.
