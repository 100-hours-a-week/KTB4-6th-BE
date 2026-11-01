# Meety 전역 AI 채팅 API 명세

이 문서는 현재 `feat/global-assistant-chat` 브랜치의 Controller, DTO, Service 구현을 기준으로 작성했습니다. FE·AI 연동 시 서버에 배포된 브랜치와 계약이 다를 수 있으므로 E2E 전에 배포 버전을 확인하세요.

## 공통 사항

- 사용자 인증: 기존 Access Token 쿠키. 기본 쿠키명은 `accessToken`이며 `AUTH_ACCESS_TOKEN_COOKIE_NAME`으로 바꿀 수 있습니다. 요청에 `Cookie: accessToken=<JWT>`를 보냅니다. 별도의 Bearer 인증은 이 API에서 정의하지 않습니다.
- JSON 응답은 `ApiResponse` 래퍼를 사용합니다.

```json
{"success":true,"data":{},"error":null}
```

- 오류 응답은 일반적으로 아래 형식입니다. 세부 검증 오류는 공통 예외 처리에 따릅니다.

```json
{"success":false,"data":null,"error":{"code":"ERROR_CODE","message":"오류 설명"}}
```

## FE → BE 공개 API

### 1. 전역 AI 질문 및 SSE 스트림

`POST /api/v1/assistant/chat`

Headers:

```http
Cookie: accessToken=<JWT>
Idempotency-Key: <클라이언트 생성 키, 1~100자>
Content-Type: application/json
Accept: text/event-stream
```

Request:

```json
{"teamId":123,"question":"최근 회의에서 결정된 내용 알려줘"}
```

`teamId`는 필수 정수이고 `question`은 공백이 아닌 1~1000자입니다. 사용자는 해당 팀의 ACTIVE 구성원이어야 합니다. 전역 채팅은 크레딧을 차감하지 않습니다.

성공 시 HTTP `200`, `Content-Type: text/event-stream`, `Cache-Control: no-cache`, `X-Ai-Request-Id: <정수 ID>`를 반환합니다. 스트림은 서버가 AI 처리를 시작하기 전에 요청을 접수한 뒤 열립니다.

SSE 이벤트:

| event | data payload | 의미 |
| --- | --- | --- |
| `status` | `{"aiRequestId":123,"status":"ACCEPTED"}` 또는 `{"aiRequestId":123,"status":"PROCESSING"}` | 초기 상태. 처리 중 phase 이벤트는 `{"phase":"SEARCHING_MEETINGS"}` 형태입니다. |
| `delta` | `{"text":"응답 일부"}` | AI가 생성한 본문 조각 |
| `ping` | `{}` | 연결 유지 이벤트. 현재 BE는 AI의 SSE 주석 ping을 JSON 이벤트로 전달합니다. |
| `completed` | `{"aiRequestId":123,"answer":"최종 답변","referencedMeetingIds":[10],"meetingDraft":{...}}` | 완료된 답변. `meetingDraft`는 선택 필드입니다. |
| `failed` | `{"aiRequestId":123,"code":"timeout","message":"답변을 생성하지 못했습니다. 다시 시도해주세요."}` | 실패 종료 |

`status` phase 값은 AI 계약상 `SEARCHING_MEETINGS`, `READING_MEETING`, `PREPARING_MEETING`입니다. 서버는 `completed`에서만 최종 답변을 저장합니다. `failed` 또는 terminal event 없는 스트림 종료는 실패 처리됩니다.

`meetingDraft`는 다음의 기존 `MeetingCreateRequest` 형식을 사용합니다.

```json
{
  "title":"주간 회의",
  "purpose":"이번 주 진행 상황 공유",
  "note":null,
  "targetDurationMinutes":30,
  "attendeeTeamMemberIds":[5,6],
  "customAttendeeNames":[],
  "scheduledAt":"2026-11-03T15:00:00"
}
```

필수 필드가 빠지거나 현재 팀의 활성 참가자 확인에 실패한 초안은 응답의 `meetingDraft`에 포함되지 않습니다. AI의 `completed`는 실제 회의 생성이 아닙니다.

오류:

| HTTP | code | 조건 |
| --- | --- | --- |
| 400 | `INVALID_IDEMPOTENCY_KEY` | 키가 비었거나 100자를 초과 |
| 400 | 공통 validation 오류 | 질문 누락/공백/1000자 초과 또는 잘못된 요청 |
| 401 | 인증 오류 | Access Token 누락 또는 무효 |
| 403 | `ASSISTANT_CHAT_ACCESS_DENIED` | 현재 팀 ACTIVE 구성원이 아님 |
| 409 | `IDEMPOTENCY_KEY_REUSED` | 같은 키로 다른 질문 또는 다른 요청 종류를 제출 |
| 409 | `ASSISTANT_CHAT_ALREADY_PROCESSING` | 대화에 이미 처리 중인 질문이 있음 |

멱등성은 `Idempotency-Key`를 사용자 팀 구성원과 팀 ID로 범위화해 저장합니다. 같은 사용자·팀·키·질문은 기존 요청을 다시 반환합니다. 같은 키로 질문이 다르면 `409`입니다. 새 요청의 기본 자동 재시도는 하지 마세요. 이전 요청의 SSE 접속이 끊겼다면 아래 이벤트 재접속 API를 사용하세요.

### 2. 채팅 이력 조회

`GET /api/v1/assistant/chat/teams/{teamId}/messages?cursor={messageId}&size={pageSize}`

Headers: `Cookie: accessToken=<JWT>`

Query:

- `cursor`: 선택. 이 ID보다 오래된 메시지를 조회합니다.
- `size`: 선택. 기본 20, 허용 범위 1~50.

Response `200`:

```json
{
  "success":true,
  "data":{
    "messages":[{
      "aiRequestId":123,"messageId":456,"question":"질문",
      "answer":"답변 또는 null","status":"COMPLETED","failureCode":null,
      "meetingDraft":null,"referencedMeetingIds":[10],
      "createdAt":"2026-11-01T10:00:00","updatedAt":"2026-11-01T10:00:05"
    }],
    "nextCursor":456,"hasNext":false
  },
  "error":null
}
```

활성 팀 구성원만 조회할 수 있습니다. 접근 불가 시 `403 ASSISTANT_CHAT_ACCESS_DENIED`; 잘못된 페이지 값은 `400`입니다. 대화가 아직 없으면 빈 `messages`가 반환됩니다.

### 3. 요청 상태·결과 조회

`GET /api/v1/assistant/chat/requests/{requestId}`

Headers: `Cookie: accessToken=<JWT>`

Response `200`의 `data`는 채팅 이력의 항목과 같은 `AssistantChatTurnResponse`입니다.

```json
{"success":true,"data":{"aiRequestId":123,"messageId":456,"question":"질문","answer":null,"status":"PROCESSING","failureCode":null,"meetingDraft":null,"referencedMeetingIds":[],"createdAt":"2026-11-01T10:00:00","updatedAt":"2026-11-01T10:00:01"},"error":null}
```

다른 사용자의 요청 ID는 접근 거부(`403 ASSISTANT_CHAT_ACCESS_DENIED`)됩니다. 현재 코드에서는 찾을 수 없는 요청도 같은 접근 거부 응답을 사용합니다.

### 4. SSE 이벤트 재접속

`GET /api/v1/assistant/chat/requests/{requestId}/events`

Headers: `Cookie: accessToken=<JWT>`, `Accept: text/event-stream`

응답은 `200 text/event-stream`입니다. 재접속 시 현재 `status`를 보낸 뒤 이미 terminal 상태면 `completed` 또는 `failed` 결과를 보냅니다. 이미 전송된 `delta` 이력은 재생하지 않으며, emitter와 실시간 delta 전달은 현재 BE 인스턴스 메모리에 있습니다. 다른 인스턴스로 연결되거나 프로세스가 재시작된 경우 실시간 재접속 보장은 미구현입니다. 확정된 결과는 상태 조회 API에서 확인하세요.

### 5. 회의 초안 승인 및 실제 회의 생성

별도의 assistant 승인 URL은 없습니다. 사용자에게 초안을 보여주고 확인을 받은 뒤 기존 API를 호출합니다.

`POST /api/v1/teams/{teamId}/meetings`

Headers:

```http
Cookie: accessToken=<JWT>
X-Ai-Request-Id: <completed 전역 채팅 요청 ID>
Content-Type: application/json
```

Body는 `meetingDraft`와 동일한 `MeetingCreateRequest`입니다. 승인 시 AI 응답에 저장된 초안과 필드 값이 정확히 같아야 합니다. 다른 팀/사용자의 요청, 미완료 요청, 초안이 아닌 요청, 본문 불일치는 `409 ASSISTANT_MEETING_DRAFT_NOT_CONFIRMED`로 거부됩니다. 성공하면 기존 `MeetingService.createMeeting()`의 권한, 참석자 검증, 일일 제한을 적용하고 HTTP `201`과 `MeetingCreateResponse` 래퍼를 반환합니다.

```json
{"success":true,"data":{"meetingId":789,"teamId":123,"title":"주간 회의","purpose":"이번 주 진행 상황 공유","note":null,"scheduledAt":"2026-11-03T15:00:00","targetDurationMinutes":30,"status":"WAITING","attendees":[]},"error":null}
```

현재 구현은 `X-Ai-Request-Id`와 요청 본문 hash로 이미 승인된 동일 요청을 재호출했을 때 기존 회의를 반환하고, 다른 본문이면 거부합니다. 별도 `Idempotency-Key` 계약은 없습니다. 회의 생성과 승인 기록은 같은 서비스 트랜잭션에서 처리되도록 구현되어 있으나, 실제 MySQL 동시 승인/원자성 E2E는 아직 미검증입니다.

초안의 `scheduledAt`은 시간대 없는 `LocalDateTime` 문자열로 전송합니다. 서비스 계약상 KST로 해석합니다.

## BE ↔ AI 계약

AI 호출은 설정된 기존 analysis 서버 주소(`ai.http-url`)를 사용합니다.

### `POST /v1/assistant/chat`

요청 헤더: `Content-Type: application/json`, `Accept: text/event-stream`. BE 요청 본문:

```json
{
  "aiRequestId":123,
  "teamId":10,
  "question":"이번 주 회의에서 결정한 내용은?",
  "recentMessages":[
    {"role":"user","content":"이전 질문"},
    {"role":"assistant","content":"이전 답변"}
  ]
}
```

`recentMessages`는 최대 20개이며 오래된 순으로 보냅니다. 현재 질문은 배열에 포함되지 않습니다. 사용자·팀 권한 판단과 내부 API 호출 상관관계에는 `aiRequestId`를 사용합니다.

성공은 `200 text/event-stream`입니다. 처리 이벤트는 `status`, `delta`, `completed`, `failed`; SSE 주석 ping은 주기적으로 수신됩니다. `completed` 필수 값은 일치하는 `aiRequestId`와 비어 있지 않은 `answer`이며 `referencedMeetingIds`는 선택 배열, `meetingDraft`는 선택 객체입니다. `failed`의 허용 코드는 `invalid_request`, `forbidden`, `timeout`, `retrieval_failed`, `generation_failed`, `execution_limit_exceeded`입니다. 실제 회의 생성 API는 제공하지 않습니다.

BE 설정 기본값은 연결 timeout 3초, socket read timeout 30초, AI 실행 제한 180초, 전체 SSE 제한 200초입니다. HTTP 422/503, 연결 오류/timeout, terminal 이벤트 없이 끝난 스트림은 요청 실패 처리됩니다. 스트림이 시작된 뒤 AI 요청을 자동 재시도하지 않습니다. 전체 deadline은 단조 시간으로 확인하며 ping만으로 제한 시간을 연장하지 않습니다.

### AI → BE 내부 조회 API

모든 내부 경로에는 `X-Internal-Api-Key`가 필요합니다. 전역 채팅 요청의 데이터 권한은 `X-Ai-Request-Id`로 연결된 `GLOBAL_CHAT` 요청, 팀 및 현재 ACTIVE 소속을 기준으로 확인합니다. API key 값은 환경 설정이며 FE에 전달하지 마세요. 응답은 `ApiResponse` 래퍼입니다.

| Method 및 경로 | Query | 식별/권한 |
| --- | --- | --- |
| `GET /internal/v1/qna/teams/{teamId}/meetings` | 선택: `keyword`, `from`, `to`(ISO date), `cursor` | `X-Ai-Request-Id` 또는 기존 녹음 경로의 `X-Recording-Session-Id` 중 하나 |
| `GET /internal/v1/qna/meetings/{meetingId}/transcripts` | 선택: `keyword` | 동일 식별 헤더. 회의와 요청/녹음 팀 일치 검증 |
| `GET /internal/v1/qna/meetings/{meetingId}/summaries` | 없음 | 동일 식별 헤더. 기존 CHAT 및 녹음 경로 호환 |
| `GET /internal/v1/qna/meetings/{meetingId}/metrics` | 없음 | `X-Ai-Request-Id`; 전역 채팅 요청과 팀 소속 검증 |
| `GET /internal/v1/assistant/team-members` | 없음 | `X-Ai-Request-Id`; 전역 채팅 요청과 현재 ACTIVE 팀 소속 검증 |

신규 지표 응답 `data`:

```json
{"metrics":{"offTopic":{},"agendas":[]},"speechBalanceScore":72.5,"contextOutDurationMs":120000}
```

각 지표 값은 데이터가 없거나 계산할 수 없으면 `null`일 수 있습니다. 신규 팀원 API 응답 `data`:

```json
{"members":[{"teamMemberId":5,"displayName":"위글"}]}
```

기존 회의 목록·전사·요약 결과는 각각 `MeetingListResponse`, `TranscriptSegmentListResponse`, `InternalSummaryResponse`의 실제 DTO를 래핑합니다. 상세 중첩 필드는 각 API 응답 DTO 스키마에 따릅니다. AI 직접 회의 생성용 `/internal/v1/assistant/meetings`는 없습니다.

잘못된 내부 키는 인증 거부됩니다. AI 요청 ID와 녹음 세션 ID를 모두 보내거나 둘 다 보내지 않은 경우, 잘못된 요청 상태, 다른 팀/회의 또는 현재 팀에서 나간 구성원은 거부됩니다. 구체 HTTP 오류 코드는 해당 공통/도메인 오류 응답을 따르며 일부 내부 오류 조합은 TBD입니다.

## 연동 전 확인할 제약

- 실 AI 서버 및 FE 브라우저를 포함한 E2E는 아직 실행하지 않았습니다. 현재 테스트는 BE 단위/격리 MySQL 및 AI SSE mock 검증입니다.
- 실시간 SSE emitter와 delta는 인스턴스 로컬 메모리입니다. 다중 BE 인스턴스 간 delta 중계는 지원하지 않습니다.
- SSE 재접속 시 이전 delta replay는 없습니다. 최종 상태/결과는 DB 기반 상태 조회를 사용합니다.
- 무료 사용량 제한은 구현되어 있지 않습니다.
- 새 전역 채팅 및 회의 승인 엔티티가 추가됐습니다. 현재 저장소는 profile별 Hibernate DDL `update`/`create`를 사용하며 버전 관리형 migration 파일은 확인되지 않았습니다. 배포 전 대상 profile의 스키마 동작과 신규 테이블 반영을 확인해야 합니다.
