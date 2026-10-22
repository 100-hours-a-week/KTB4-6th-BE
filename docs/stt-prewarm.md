# #342 예정 회의 STT prewarm

## 목적과 흐름

실시간 AI의 모델 cold start가 첫 전사를 늦출 수 있으므로, BE는 시작이 임박한 회의 정보를 AI에 미리 전달한다.
매 실행마다 DB에서 다음 조건을 조회한다.

```text
status = WAITING
deleted_at is null
now(KST) <= scheduled_at < now(KST) + lookahead
order by scheduled_at, id
```

기본 실행 간격은 완료 후 10초(`fixedDelay`), 선행 조회 구간은 1분이다.
기본 Spring `taskScheduler`에서 같은 작업의 이전 실행이 끝난 후 다음 실행을 예약한다.
AI HTTP 호출은 DB 조회 트랜잭션 밖에서 회의별로 수행한다.
녹음 시작 시 Meeting이 `IN_PROGRESS`로 바뀌므로 별도 RecordingSession 조회는 하지 않는다.

## BE → AI 계약

```http
POST /internal/stt/prewarm
Content-Type: application/json
```

```json
{"meetingId":123,"startsAt":"2026-10-22T15:00:00+09:00"}
```

`scheduledAt`은 #303의 오프셋 없는 KST `LocalDateTime`이다.
`scheduledAt.atZone(Asia/Seoul).toOffsetDateTime()`으로 같은 현지 시각에 `+09:00`을 붙인다.
BE는 `202 Accepted`를 요청 접수로 기록한다. AI 준비 완료 신호로 취급하지 않는다.

기존 AI `RestClient` 설정과 `ai.http-url`을 기본으로 재사용한다.
실시간 AI의 HTTP endpoint가 다른 호스트/포트에 있다면 `AI_STT_PREWARM_HTTP_URL`로 지정한다.
기존 AI HTTP 호출에는 BE → AI 인증 헤더가 없다. BE의 `X-Internal-Api-Key`는 AI → BE 수신용이다.
따라서 AI prewarm의 별도 인증 방식과 endpoint 배치는 AI 팀과 실제 통합 시 확정해야 한다.
현재 구현은 새로운 인증 헤더를 임의로 추가하지 않는다.

HTTP 연결 timeout은 기존 AI Client 설정인 3초를 사용한다.
Prewarm 읽기 timeout 기본값은 3초이며 `AI_STT_PREWARM_READ_TIMEOUT`으로 조정 가능하다.
이 호출은 기존 요약·지표용 60초 읽기 timeout을 사용하지 않는다.

## 실패와 중복

- `422`, `401`, `403`, `5xx`, 기타 `202`가 아닌 status는 실패로 기록한다.
- 연결 실패와 timeout은 해당 회의만 실패로 기록하고 다음 회의를 계속 처리한다.
- 현재 실행에서 즉시 재시도하지 않는다. 다음 실행 시에도 조회 구간에 남아 있으면 다시 요청한다.
- BE는 완료 플래그, Redis 중복 방지, worker 수요를 저장하지 않는다.
- 여러 BE 인스턴스가 같은 회의를 전송할 수 있다. AI가 `meetingId`로 반복 요청을 통합한다.
- AI의 초기 준비 수요 만료 정책은 `startsAt + 3분`이다. 준비/만료/worker 관리는 AI 책임이다.
- Prewarm 실패·누락은 녹음 시작, 크레딧 차감, AI `session.start`의 선행 조건이 아니다.

`ai.stt.prewarm.scheduler.runs`, `meetings`, `accepted`, `failures{reason}`, `timeouts`로 관측한다.
`meetingId`, `scheduledAt`, `startsAt`, HTTP status는 로그에만 기록한다.

## 성능과 남은 확인

현재 코드/리포지토리에는 `meetings(status, deleted_at, scheduled_at)` 전용 인덱스가 선언되어 있지 않다.
회의 조회량이 증가하면 실제 MySQL `EXPLAIN`과 테이블 크기를 측정해 인덱스를 검토한다.
이번에는 스키마를 변경하지 않는다.

실제 AI와 다음을 확인해야 한다.

- `AI_STT_PREWARM_HTTP_URL`이 `/internal/stt/prewarm`을 제공하는 실시간 AI 주소인지
- BE → AI 인증 헤더/키 계약
- JSON 시간대, `202/422/401/403/5xx` 응답과 timeout 동작
- 반복 요청의 AI 측 `meetingId` 통합 및 `startsAt + 3분` 만료
- GPU cold start p50/p95, 요청부터 worker 준비까지의 시간, prewarm 요청량과 DB 조회량

그 실측값을 바탕으로 10초 간격, 1분 선행 시간, 3초 읽기 timeout을 조정한다.
