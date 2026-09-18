# Meety 현재 API 및 검증 분석

분석일: 2026-09-18 / 브랜치: `feat/#25-home-api`

현재 저장소 구현을 설명하는 문서다. 별도 API 명세·Tech Spec·ERD는 저장소에서 찾지 못했다. 아래의 구현 사실은 확정 정책과 구분해야 한다. 애플리케이션 코드는 수정하지 않았다.

## 1. 구현 범위

- Java 25, Spring Boot 4.0.8, Spring MVC, JPA, Spring Security, Bean Validation, MySQL, JJWT.
- Controller 5개, 업무 HTTP API 17개. 별도로 Actuator health가 공개된다.
- 인증, 회원 탈퇴, 팀 생성/조회, 회의 생성/조회/수정/삭제/참여, 홈 집계 구현.
- 녹음, 전사, AI, 크레딧, 알림, 리포트는 엔티티가 있으나 Controller/업무 Service API는 없다. 회의 시작·종료 API도 없다.
- 팀 가입/탈퇴/강퇴/차단/팀장 위임/초대 코드 재생성 API는 없다.
- 프로젝트 지침의 Redis/S3/WebSocket은 현재 구현된 통합과 다르다. Refresh Token은 MySQL JPA 테이블에, Access Token 블랙리스트는 JVM 메모리에 저장한다. 해당 외부 통합용 의존성은 build.gradle에 없다.

## 2. 공통 HTTP 계약

업무 API prefix는 `/api/v1`이다. 아래 표 URI는 prefix를 생략했다.

성공 응답:

```json
{"success":true,"data":{},"error":null}
```

실패 응답:

```json
{"success":false,"data":null,"error":{"code":"INVALID_INPUT_VALUE","message":"입력값이 올바르지 않습니다."}}
```

- `204`인 회원 탈퇴·회의 삭제·참여 취소는 응답 본문이 없다.
- 로그아웃은 `200`, `data:null`이다.
- 인증은 **`Cookie: accessToken=<JWT>`** 기준이다. `Authorization: Bearer`는 현재 필터에서 읽지 않는다.
- 로그인/재발급은 토큰을 JSON으로 반환하며, 서버에서 Set-Cookie를 설정하거나 로그아웃 시 쿠키를 삭제하는 코드가 없다.
- 로그인, 재발급, 로그아웃은 인증 없이 접근 가능하다. `/actuator/health`도 공개된다. 나머지 경로는 인증 필요.
- 사용자 식별은 `@AuthenticationPrincipal Long userId`를 사용한다. 사용자 ID를 업무 요청 body에서 받지 않는다.
- 날짜는 `LocalDate`/`LocalDateTime`이다. 예정 시간에는 offset/zone 필드가 없다. 홈의 오늘 범위와 일일 생성 제한은 KST 기준이다.

근거: [Controller](../src/main/java/com/backend/meety/domain/meeting/controller/MeetingController.java), [SecurityConfig](../src/main/java/com/backend/meety/global/security/SecurityConfig.java), [JWT 필터](../src/main/java/com/backend/meety/global/security/JwtAuthenticationFilter.java), [공통 응답](../src/main/java/com/backend/meety/global/response/ApiResponse.java).

## 3. 전체 API

| # | Method / URI | 입력 | 성공 | 권한·비즈니스 검증 / 주요 실패 |
|---|---|---|---|---|
| 1 | POST `/auth/{provider}/login` | provider, authorizationCode | 200 LoginResponse | 현재 kakao만 지원. provider 미지원 400, 인가 코드/카카오 인증 실패 401, 처리 실패 500 |
| 2 | POST `/auth/token/refresh` | refreshToken | 200 TokenRefreshResult | 토큰 해시 조회, 만료 확인, 미사용 토큰의 원자적 사용 처리. 미존재/사용됨/경합 패배/만료 401 |
| 3 | POST `/auth/logout` | accessToken, refreshToken | 200 data:null | RT 사용 처리, 유효한 AT 블랙리스트 등록. 존재하지 않는 RT·잘못되거나 만료된 AT도 형식 검증을 통과하면 정상 종료 가능 |
| 4 | DELETE `/users/me` | 없음 | 204 | 사용자 조회, 이미 탈퇴한 경우 반환, OAuth 연결 해제 후 회원/계정 soft delete 및 RT 전체 폐기. 실패 500 |
| 5 | POST `/teams` | name, displayName | 201 TeamCreateResponse | 삭제되지 않은 사용자 row lock, 활성 팀 중복 확인, 팀+팀장+초대 코드 생성. 사용자 없음 404, 중복 409, 생성 실패 500 |
| 6 | GET `/teams/me` | 없음 | 200 MyTeamResponse | ACTIVE 멤버십 조회. 미가입은 오류가 아닌 hasActiveTeam:false |
| 7 | GET `/teams/{teamId}` | teamId | 200 TeamDetailResponse | 삭제되지 않은 팀 404, ACTIVE 소속 없음 403. 활성 초대 코드 없으면 현재 일반 500 |
| 8 | POST `/teams/{teamId}/meetings` | teamId, MeetingCreateRequest | 201 MeetingCreateResponse | 팀 lock/존재 → 당일 생성 5개 제한 → ACTIVE 소속 순서. 404/409/403/저장 실패 500 |
| 9 | GET `/meetings/{meetingId}` | meetingId | 200 MeetingDetailResponse | 삭제되지 않은 회의 404, 해당 팀 ACTIVE 소속 403 |
| 10 | GET `/teams/{teamId}/meetings` | keyword, from, to, cursor, size | 200 MeetingListResponse | 팀 404, ACTIVE 소속 403, 날짜 역전·size·cursor 오류 400 |
| 11 | GET `/teams/{teamId}/meeting-calendar` | year, month 필수 | 200 MeetingCalendarResponse | 팀 404, ACTIVE 소속 403, 월 범위·날짜 생성 오류 400 |
| 12 | PATCH `/meetings/{meetingId}` | MeetingUpdateRequest | 200 MeetingUpdateResponse | 삭제되지 않은 회의 lock, ACTIVE 소속 + 팀장 또는 생성자. 404/403/허용 안 되는 상태·필드 409 |
| 13 | DELETE `/meetings/{meetingId}` | meetingId | 204 | 삭제되지 않은 회의 lock, ACTIVE 팀장만. 진행 중 삭제 금지. 404/403/409 |
| 14 | POST `/meetings/{meetingId}/participants` | body 없음 | 201 MeetingParticipantResponse | 회의 404, ACTIVE 소속 403, WAITING/IN_PROGRESS만. 중복 참여/연결 끊김 등 409 |
| 15 | GET `/meetings/{meetingId}/participants` | body 없음 | 200 MeetingParticipantListResponse | 회의 404, ACTIVE 소속 403. JOINED이고 deletedAt=null인 참여자만 조회 |
| 16 | DELETE `/meetings/{meetingId}/participants/me` | body 없음 | 204 | 회의/참여자 404, ACTIVE 소속 403, 허용 회의 상태 및 JOINED 여부 확인. 이미 나감/연결 끊김 등 409 |
| 17 | GET `/home` | 없음 | 200 HomeResponse | ACTIVE 소속 없으면 403 ACTIVE_TEAM_REQUIRED. 팀/집계/지표/오늘 회의 조합 |

인증이 필요한 모든 API에는 공통 401이 추가된다. Body/query 바인딩 오류는 일반적으로 400이다. 예상하지 못한 예외는 공통 500으로 변환된다.

## 4. Request Validation 전체

### 4.1 DTO

| DTO / 필드 | 적용 검증 | 허용/주의 |
|---|---|---|
| LoginRequest.authorizationCode | @NotBlank | null, 빈 문자열, 공백만 있는 값 금지. 길이 제한 없음 |
| LogoutRequest.accessToken / refreshToken | 각각 @NotBlank | 형식/서명/사용자 일치 검증은 Bean Validation에 없음 |
| TokenRefreshRequest.refreshToken | @NotBlank | 토큰 존재/만료/사용 여부는 Service |
| TeamCreateRequest.name | @NotBlank, @Size(max=20) | 양끝 공백 자동 제거 없음 |
| TeamCreateRequest.displayName | @NotBlank, @Size(max=10) | 양끝 공백 자동 제거 없음 |
| MeetingCreateRequest.title | @NotBlank, @Size(max=20) | 필수 |
| MeetingCreateRequest.purpose | @NotBlank, @Size(max=100) | 필수 |
| MeetingCreateRequest.note | @Size(max=200) | null, 빈 문자열, 공백 허용 |
| MeetingCreateRequest.scheduledAt | @NotNull | **과거 시간도 허용**. @Future 없음 |
| MeetingCreateRequest.targetDurationMinutes | @NotNull, @Min(1), @Max(60) | 1~60분 |
| MeetingUpdateRequest.title | @Size(max=20), @AssertTrue 메서드 | null은 유지, 값이 있으면 blank 금지 |
| MeetingUpdateRequest.purpose | @Size(max=100), @AssertTrue 메서드 | null은 유지, 값이 있으면 blank 금지 |
| MeetingUpdateRequest.note | @Size(max=200) | null은 유지, 빈 문자열은 메모 삭제, 공백 문자열은 저장 |
| MeetingUpdateRequest.scheduledAt | @Future | null은 유지, 제공하면 현재 시각 이후 |
| MeetingUpdateRequest.targetDurationMinutes | @Min(1), @Max(60) | null은 유지, 제공하면 1~60 |

모든 Body DTO는 Controller에서 `@Valid`를 사용한다. PATCH의 `{}` 및 모든 필드 null은 WAITING/COMPLETED에서 변경 없는 성공이다. IN_PROGRESS에서는 빈 요청도 409이다. 명시적 JSON null과 필드 생략은 구분하지 않는다.

근거: [인증 DTO](../src/main/java/com/backend/meety/domain/auth/dto/LoginRequest.java), [팀 생성](../src/main/java/com/backend/meety/domain/team/dto/TeamCreateRequest.java), [회의 생성](../src/main/java/com/backend/meety/domain/meeting/dto/MeetingCreateRequest.java), [회의 수정](../src/main/java/com/backend/meety/domain/meeting/dto/MeetingUpdateRequest.java).

### 4.2 경로와 query

| 입력 | 현재 처리 |
|---|---|
| teamId / meetingId | Long 변환. 숫자 아닌 값은 400. @Positive 없음: 0/음수는 보통 조회 후 404 |
| provider | 문자열 그대로 Map 조회, 현재 소문자 kakao만 지원 |
| keyword | trim 후 blank이면 검색 조건 제거. 제목 LIKE 부분 검색. 길이 제한 없음 |
| from / to | ISO 날짜. 선택 입력. 둘 다 있으면 from ≤ to 필요 |
| 날짜 범위 | from 당일 00:00 이상, to 다음 날 00:00 미만 |
| size | 생략 20, 유효 범위 1~50. Service가 INVALID_PAGE_SIZE 반환 |
| cursor | null/blank는 첫 페이지. URL-safe Base64 내부 `LocalDateTime|Long` 파싱. 오류 시 INVALID_CURSOR |
| year | 필수 Integer. LocalDate 생성 가능 여부로 추가 확인. 업무상 허용 연도 범위 없음 |
| month | 필수 Integer, @NotNull, @Min(1), @Max(12). Controller @Validated |

cursor에는 양수 ID·실제 회의 존재·팀/검색 조건 일치 검증은 없다. 다만 조회 자체에 teamId와 소속 검증이 있어 커서만으로 다른 팀 데이터가 반환되는 구조는 아니다. keyword의 `%`, `_`는 이스케이프하지 않아 LIKE wildcard로 작동한다. 바인딩 파라미터를 사용하므로 이를 SQL 문자열 삽입과 혼동하면 안 된다.

## 5. 응답 data 필드

| 응답 DTO | 필드 |
|---|---|
| LoginResponse | userId, accessToken, refreshToken |
| TokenRefreshResult | accessToken, refreshToken |
| TeamCreateResponse | teamId, name, teamMemberId, displayName, role, membershipStatus, invitationCode, createdAt |
| MyTeamResponse | hasActiveTeam, teamId. 팀이 없으면 @JsonInclude(NON_NULL)로 teamId 필드 생략 |
| TeamDetailResponse | teamId, name, teamMemberId, displayName, role, invitationCode, createdAt |
| MeetingCreateResponse | meetingId, teamId, createdByTeamMemberId, title, purpose, note, scheduledAt, targetDurationMinutes, status, createdAt |
| MeetingDetailResponse | 생성 응답 필드 + startedAt, endedAt, updatedAt |
| MeetingUpdateResponse | meetingId, teamId, createdByTeamMemberId, title, purpose, note, scheduledAt, targetDurationMinutes, status, startedAt, endedAt, updatedAt |
| MeetingListResponse | groups, nextCursor, hasNext |
| MeetingGroupResponse | date, meetingCount, meetings |
| MeetingListItemResponse | meetingId, title, scheduledAt, startedAt, endedAt, targetDurationMinutes, status |
| MeetingCalendarResponse | year, month, dates |
| MeetingCalendarDateResponse | date, meetings |
| MeetingCalendarItemResponse | meetingId, title, status, scheduledAt, startedAt, endedAt |
| MeetingParticipantResponse | participantId, teamMemberId, displayName, participationStatus, createdAt |
| MeetingParticipantListResponse | participantsCount, participants |
| HomeResponse | team, meetingSummary, meetingMetrics, todayMeetingCount, todayMeetings |
| HomeTeamResponse | teamId, name, invitationCode, memberCount |
| HomeMeetingSummaryResponse | totalMeetingCount, totalMeetingMinutes |
| HomeMeetingMetricsResponse | averageMeetingMinutes, speechBalanceScore |
| HomeTodayMeetingResponse | meetingId, title, status, scheduledAt, startedAt, endedAt |

### 목록·캘린더

- 기준 시각은 WAITING이면 scheduledAt, 나머지는 startedAt.
- 회의 목록: 날짜 내림차순 → 같은 날짜 내 시각 오름차순 → ID 오름차순.
- size+1개 조회로 hasNext를 계산하고 마지막 반환 항목으로 nextCursor를 생성한다.
- meetingCount는 해당 페이지의 날짜 그룹 개수다. 하루 전체 개수가 아니며 날짜 그룹이 페이지 사이에서 나뉠 수 있다.
- 캘린더는 해당 월 전체를 기준 시각/ID 오름차순으로 반환한다. 회의 없는 날짜는 생성하지 않는다.
- 참여자 목록은 최초 createdAt/ID 오름차순. 재참여 시 기존 row를 복구하므로 createdAt은 최초 참여 시각이다.

### 홈 집계

- 삭제되지 않은 COMPLETED 회의의 수와 회의별 `timestampdiff(minute, started_at, ended_at)` 합계.
- 시작/종료 시간이 null인 완료 회의는 회의 수에는 포함되고 시간은 0으로 합산된다.
- averageMeetingMinutes는 합계/개수의 정수 나눗셈. 완료 회의 0개이면 null.
- speechBalanceScore는 삭제되지 않은 완료 회의에 속한 삭제되지 않은, 점수 null이 아닌 **지표 row 전체 평균**. 회의별 최신 1개 선택 로직은 없다.
- 오늘 회의는 KST 당일 기준 시각으로 조회한다. 어제 시작한 진행 중 회의는 이 조회에 포함되지 않는다.
- 홈 표시 상태만 SCHEDULED가 추가된다. WAITING 회의 중 예정 시각 전이면 SCHEDULED, 예정 시각 이상이면 WAITING. DB 상태를 변경하지 않는다.
- 초대 코드 없으면 null, 오늘 회의 없으면 빈 배열/0, 점수 없으면 null.

근거: [회의 조회 구현](../src/main/java/com/backend/meety/domain/meeting/repository/MeetingRepositoryImpl.java), [홈 Service](../src/main/java/com/backend/meety/domain/home/service/HomeService.java), [집계](../src/main/java/com/backend/meety/domain/meeting/repository/MeetingRepository.java), [지표 평균](../src/main/java/com/backend/meety/domain/meeting/repository/MeetingMetricRepository.java).

## 6. 권한·상태 검증

| 회의 상태 | 상세/목록/참여자 조회 | 수정: ACTIVE 팀장 또는 생성자 | 삭제: ACTIVE 팀장 | 참여/나가기: ACTIVE 팀원 |
|---|---|---|---|---|
| WAITING | 허용 | 제공한 필드 수정 | 허용 | 허용 |
| IN_PROGRESS | 허용 | 409 | 409 | 허용 |
| COMPLETED | 허용 | title만 허용 | 허용 | 409 |

조회에도 해당 팀 ACTIVE 멤버십이 필요하다. 생성자라는 이유만으로 탈퇴 후 수정할 수 없다. 회의 생성자가 일반 팀원이면 수정은 가능하지만 삭제는 불가능하다.

| 기존 참여 상태 | 참여 POST | 나가기 DELETE |
|---|---|---|
| row 없음 | JOINED 생성 | 404 PARTICIPANT_NOT_FOUND |
| JOINED + deletedAt=null | 409 ALREADY_PARTICIPATING | LEFT + deletedAt 기록 |
| LEFT | 기존 row JOINED 복구, deletedAt=null | 409 ALREADY_LEFT |
| DISCONNECTED | 409 PARTICIPANT_STATUS_CONFLICT | 동일 409 |
| 기타 불일치 | 409 PARTICIPANT_STATUS_CONFLICT | 동일 409 |

참여자 상태 검증보다 회의 존재/소속/회의 상태 검증이 먼저다. 현재 DISCONNECTED로 전환하는 업무 API는 없다.

## 7. 트랜잭션·동시성·DB 검증

| 동작 | 현재 구현 | 남은 범위 |
|---|---|---|
| 팀 생성 | 한 트랜잭션에 팀/팀장/코드 저장. 사용자 PESSIMISTIC_WRITE 후 ACTIVE 존재 검사 | DB에 활성 팀 1개 제약은 없음. 미래 가입 경로도 같은 사용자 잠금 규약 필요 |
| 초대 코드 | SecureRandom 기반 생성, 존재 여부 최대 5회 조회, code UNIQUE | 조회 뒤 다른 트랜잭션 INSERT 경쟁 가능. 충돌 발생 후 재발급 재시도는 없음 |
| 회의 생성 | 팀 PESSIMISTIC_WRITE 후 당일 수 조회 및 saveAndFlush | 해당 경로끼리 팀별 5개 제한 직렬화. 권한 확인이 제한 조회보다 늦음 |
| 회의 수정/삭제 | 회의 PESSIMISTIC_WRITE, 상태 변경 트랜잭션 | 참여 Service는 같은 잠금을 사용하지 않아 참여와 삭제 경합 가능 |
| 참여/나가기 | @Transactional + 조회 후 상태 변경 | 잠금/@Version/(meeting_id,team_member_id) UNIQUE가 엔티티에 없어 중복 INSERT·상태 변경 경쟁 가능 |
| RT rotation | 토큰 조회, 만료 검사, deletedAt IS NULL 조건부 UPDATE의 영향 행 1개 확인, 새 RT INSERT를 한 트랜잭션에서 처리 | 단일 토큰 재사용 방어. 탈퇴와 새 토큰 발급 사이의 사용자 수준 경합은 별도 미보장 |
| 회원 탈퇴 | 외부 unlink 후 내부 DB 트랜잭션에서 사용자/계정 삭제, RT 폐기 | 외부 unlink는 DB rollback으로 복구되지 않음. 팀 소속 종료 TODO |
| 로그인 | OAuth 외부 호출, 사용자 findOrCreate 트랜잭션, RT 발급 트랜잭션 분리 | 동일 소셜 계정 최초 동시 로그인은 UNIQUE로 중복은 막지만 패배 요청 복구/재조회 없음 |
| 조회 | 팀/회의/참여자/홈 Service @Transactional(readOnly=true) | 다중 조회의 실제 일관성은 DB isolation에 의존 |

확인된 UNIQUE: 소셜 계정 `(provider, provider_user_id)`, refresh_tokens.token_hash, 초대 코드 code, AI 결과 일부 ai_request_id. `nullable=false`/문자열 길이/FK는 JPA 매핑에 존재한다. DB 운영 실물 스키마를 별도로 대조하지 않았으므로 운영 제약 보장을 의미하지 않는다.

## 8. 예외 처리

- 도메인 예외는 BusinessException을 상속하며 BaseCode 구현 enum을 사용한다.
- BusinessException: ErrorCode의 HTTP status/code/message 반환.
- MethodArgumentNotValidException: 첫 field error 메시지와 INVALID_INPUT_VALUE/400.
- ConstraintViolationException: 첫 violation 메시지와 INVALID_INPUT_VALUE/400.
- HandlerMethodValidationException, JSON 파싱/본문 누락, 필수 query 누락, 타입 불일치: 일반 INVALID_INPUT_VALUE/400.
- 기타 Exception: 로그 후 INTERNAL_SERVER_ERROR/500.
- JWT 필터의 인증 오류는 ControllerAdvice 대신 CustomAuthenticationEntryPoint에서 공통 응답으로 처리.
- 검증 실패 메시지는 enum 고정 메시지가 아니라 DTO annotation 메시지일 수 있다. 모든 오류를 배열로 제공하지 않으며 여러 오류의 첫 메시지 선택에 의존하면 안 된다.
- `TEAM_MEMBERSHIP_REQUIRED`는 조회에도 재사용하지만 메시지는 “회의를 생성할 수 있습니다”로 생성 전용 표현이다.
- `INVALID_MEETING_KEYWORD`는 선언되어 있으나 현재 검색어 검증에서 사용하지 않는다.
- 팀 상세에서 초대 코드가 없으면 IllegalStateException을 던진다. 도메인 예외가 아닌 불변식 오류 처리다.
- 잘못된 HTTP method/media type 등 MVC 예외는 전용 매핑이 없다. catch-all 500으로 처리되는지 HTTP 회귀 검증이 필요하다.

근거: [전역 예외 처리](../src/main/java/com/backend/meety/global/exception/GlobalExceptionHandler.java).

## 9. 개선·정책 확인 항목

다음은 코드에서 확인한 누락/위험과 정책 확인 사항이다. 수정 완료를 의미하지 않는다.

### 우선 처리

1. **테스트 DB 격리 부재 — 실행에서 확인됨.** `MeetyApplicationTests`는 별도 테스트 프로필 없이 @SpringBootTest를 사용한다. 이번 전체 테스트에서 local 프로필의 ddl-auto:create가 적용되어 연결된 로컬 DB 테이블 drop/create가 로그에 기록되었다. 기존 데이터 유무는 확인하지 못했다. 실행 전 격리를 확인하지 못한 분석 작업의 실수이며, 이후 DB 실행을 중단했다. 전용 테스트 DB/프로필을 분리하기 전 전체 context 테스트 재실행을 피해야 한다.
2. **탈퇴 후 기존 AT의 업무 접근 차단 누락.** 탈퇴는 RT만 폐기하고 AT 블랙리스트 등록/필터의 사용자 탈퇴 확인은 없다. 팀 멤버십 종료도 TODO이므로 기존 AT와 ACTIVE 소속이 남으면 회의·팀·홈 API 접근이 계속 가능한 코드 경로다. [UserService](../src/main/java/com/backend/meety/domain/user/service/UserService.java), [UserAccountService](../src/main/java/com/backend/meety/domain/user/service/UserAccountService.java), [JWT 필터](../src/main/java/com/backend/meety/global/security/JwtAuthenticationFilter.java).
3. **참여 동시 요청 중복 방어 누락.** 같은 사용자·회의의 최초 참여가 동시에 진행되면 둘 다 미존재 조회 후 저장할 수 있다. 이후 단건 Optional 조회가 다건 예외로 이어질 수 있다. 재참여/나가기/회의 삭제 사이의 경합도 보호되지 않는다. [참여 Service](../src/main/java/com/backend/meety/domain/meeting/service/MeetingParticipantService.java), [참여 Entity](../src/main/java/com/backend/meety/domain/meeting/entity/MeetingParticipant.java).
4. **블랙리스트가 인스턴스 로컬 메모리.** 재시작하면 로그아웃 AT 차단 정보가 사라지고 여러 서버에 공유되지 않는다. Redis 도입/사용 범위 변경은 프로젝트 정책에 따라 별도 설계 확인 필요. [AccessTokenBlacklist](../src/main/java/com/backend/meety/global/security/AccessTokenBlacklist.java).
5. **쿠키 인증의 CSRF 정책 미완성.** CSRF가 TODO와 함께 꺼져 있고 서버의 쿠키 발급/SameSite 정책은 없다. 실제 공격 가능성은 프런트·프록시의 쿠키 설정과 배포 구성에 좌우되므로 현재 소스만으로 확정하지 않는다. 쿠키 전달 계약과 CSRF 방어를 함께 확정해야 한다.

### 정확성·일관성

6. **삭제된 팀 필터가 경로마다 다름.** 팀 상세는 team.deletedAt을 확인하지만 회의 생성의 팀 lock/목록의 existsById/홈의 ACTIVE 소속 조회는 그렇지 않다. 팀 삭제 API는 아직 없지만 삭제된 팀과 ACTIVE 소속이 남으면 접근 가능하다.
7. **회의 생성 제한을 권한보다 먼저 검사.** 비회원도 팀이 하루 5개에 도달했다면 403 대신 409를 받는다. 권한 오류 일관성과 팀 상태 노출 최소화를 위해 순서 검토 필요.
8. **예정 시각 정책 차이.** 생성은 과거 허용, 수정은 미래만 허용. 확정 명세가 없으므로 버그라고 단정하거나 임의 변경하지 않는다.
9. **시간 기준 혼용.** 홈은 주입 Clock+KST, 생성 일일 제한은 주입 Clock을 무시한 LocalDate.now(KST), BaseEntity 감사 시간은 별도 기본 시간이다. 서버 timezone이 다르면 일일 집계 경계 검증 필요.
10. **PATCH updatedAt 응답은 이전 값일 가능성.** 응답 DTO를 dirty checking flush 전에 생성해 JPA 감사 updatedAt 갱신 전 값을 읽을 수 있다. 실제 DB 저장값과 HTTP 응답을 비교하는 통합 테스트 필요.
11. **최대 날짜 경계 입력의 500 가능성.** year=999999999/month=12는 LocalDate 생성 후 plusMonths(1)에서 예외가 난다. 목록 to=LocalDate.MAX와 커서 최대 날짜도 plusDays(1)에서 예외가 난다. 허용 업무 날짜 범위를 확정하고 400 처리 필요.
12. **RT 만료의 동등 경계.** isExpired는 expiresAt.isBefore(now)이므로 expiresAt==now는 만료가 아니다. 만료 시각 포함 여부와 DB 시간 정밀도 확인 필요.
13. **최초 소셜 로그인 경쟁.** UNIQUE 충돌은 중복 계정 저장을 방지하지만 성공 계정 재조회 없이 일반 500으로 끝날 수 있다.
14. **홈 지표의 집계 단위.** 동일 회의에 여러 지표 row가 가능하므로 회의별 평균인지 지표별 평균인지 확인해야 한다. 현재는 모든 지표 row 평균이다.
15. **초대 코드 없음 처리 차이.** 홈은 null 성공, 팀 상세는 일반 500. 불변식/빈 데이터 정책 합의 필요.
16. **로그아웃 토큰 결합 검증 없음.** body AT와 RT가 같은 사용자 것인지 확인하지 않고 독립 폐기한다. 공개 로그아웃의 멱등 처리 정책과 함께 확인 필요.

### 검증 계약 개선 후보

- 검색어 길이/LIKE wildcard 의미, ID 양수 검증, 연도 허용 범위, 생성 시 note 빈 문자열 정규화, 문자열 trim 여부.
- 204 무본문을 공통 응답 규약의 예외로 문서화할지 확인.
- DTO validation message와 ErrorCode message 중 어떤 것을 외부 계약으로 삼을지 확인.
- 신규 입력 제한은 기존 API 계약을 바꾸므로 단순 정리 작업에서 임의 적용하지 않았다.

## 10. 테스트 결과와 한계

실행: `./gradlew test --rerun-tasks`

- compileJava/compileTestJava 성공.
- 21개 테스트 클래스, 146 tests, failures 0, errors 0, skipped 0.
- 초기 `./gradlew test`는 UP-TO-DATE여서 실제 실행 확인을 위해 재실행했다.
- 재실행의 local DB 테이블 재생성은 위 9절에 기록했다.

| 영역 | 현재 검증 |
|---|---|
| 인증/회원 | Service mock 기반 정상·실패·탈퇴·RT 사용 경합 결과 처리 |
| 토큰 | 서명/만료/형식/키 길이, 블랙리스트 |
| 팀 | 생성/ACTIVE 중복/존재/접근/내 팀 등 Service, 코드 생성기 |
| 회의 | 생성 한도/조회/검색/페이지/권한/상태/수정/삭제 Service 58개 |
| 참여 | 정상/실패/권한/상태 관련 Service 19개 |
| DTO | 회의 생성/수정의 필수·길이·범위·미래 시각 |
| 홈 | ACTIVE 없음/정상 집계/빈 결과 3개 |
| Repository | 주로 @Query 문자열/@Lock annotation 확인, 동적 JPQL은 mock EntityManager 확인 |
| 애플리케이션 | 실제 Spring context 로딩 1개 |

테스트 성공이 실제 HTTP 계약/SQL 결과/동시성을 모두 보장하지는 않는다. Controller MockMvc, JWT filter 및 ExceptionHandler HTTP 통합, 실제 데이터 기반 조회/집계/커서 SQL, 멀티스레드 트랜잭션 경쟁, 탈퇴 AT 재사용, 쿠키/CSRF, OAuth 외부 오류 응답 통합 검증이 부족하다.

## 11. 후속 작업 순서 제안

1. 전용 테스트 DB/프로필 격리와 기존 로컬 데이터 영향 확인.
2. 탈퇴·로그아웃의 토큰 무효화/팀 소속 정책 확정 및 회귀 검증.
3. 참여 동시성 방안 결정: DB 제약과 잠금/원자 변경의 영향 범위 비교.
4. 쿠키 발급/전달 및 CSRF 계약 확정.
5. 날짜/soft delete/응답 updatedAt 정확성과 HTTP 예외 변환 검증.
6. 현재 API 요청·응답·권한·상태표를 확정 API 명세와 대조.

DB Schema, 인증 구조, 새 Dependency는 본 문서 작성에서 변경하지 않았다.

## 부록 A. ErrorCode 전체 목록

### AuthErrorCode

| HTTP | Code | Message |
|---|---|---|
| 401 | INVALID_AUTHORIZATION_CODE | 인가 코드를 확인해주세요. |
| 400 | UNSUPPORTED_OAUTH_PROVIDER | 지원하지 않는 소셜 로그인입니다. |
| 401 | KAKAO_AUTH_FAILED | 카카오 로그인에 실패했습니다. 다시 시도해주세요. |
| 500 | AUTH_PROCESSING_FAILED | 로그인 처리 중 오류가 발생했습니다. |
| 401 | INVALID_REFRESH_TOKEN | 유효하지 않은 리프레시 토큰입니다. |
| 401 | EXPIRED_REFRESH_TOKEN | 만료된 리프레시 토큰입니다. |
| 401 | AUTHENTICATION_REQUIRED | 로그인이 필요합니다. |
| 401 | INVALID_ACCESS_TOKEN | 유효하지 않은 토큰입니다. |
| 401 | EXPIRED_ACCESS_TOKEN | 만료된 토큰입니다. |

### HomeErrorCode

| HTTP | Code | Message |
|---|---|---|
| 403 | ACTIVE_TEAM_REQUIRED | 활성 팀이 필요합니다. |

### MeetingErrorCode

| HTTP | Code | Message |
|---|---|---|
| 404 | TEAM_NOT_FOUND | 팀을 찾을 수 없습니다. |
| 403 | TEAM_MEMBERSHIP_REQUIRED | 활성 팀원만 회의를 생성할 수 있습니다. |
| 409 | DAILY_MEETING_LIMIT_EXCEEDED | 팀은 하루에 회의를 5개만 생성할 수 있습니다. |
| 404 | MEETING_NOT_FOUND | 회의를 찾을 수 없습니다. |
| 403 | MEETING_ACCESS_DENIED | 회의에 접근할 권한이 없습니다. |
| 403 | MEETING_UPDATE_FORBIDDEN | 회의를 수정할 권한이 없습니다. |
| 403 | MEETING_DELETE_FORBIDDEN | 회의를 삭제할 권한이 없습니다. |
| 409 | MEETING_FIELD_UPDATE_NOT_ALLOWED | 현재 회의 상태에서는 요청한 필드를 수정할 수 없습니다. |
| 409 | MEETING_IN_PROGRESS | 진행 중인 회의는 삭제할 수 없습니다. |
| 409 | MEETING_PARTICIPATION_NOT_ALLOWED | 현재 회의 상태에서는 참여자 요청을 처리할 수 없습니다. |
| 409 | ALREADY_PARTICIPATING | 이미 회의에 참여 중입니다. |
| 404 | PARTICIPANT_NOT_FOUND | 회의 참석자를 찾을 수 없습니다. |
| 409 | ALREADY_LEFT | 이미 회의에서 나간 참석자입니다. |
| 409 | PARTICIPANT_STATUS_CONFLICT | 현재 참석자 상태에서는 요청을 처리할 수 없습니다. |
| 400 | INVALID_DATE_RANGE | 조회 시작일은 종료일보다 이후일 수 없습니다. |
| 400 | INVALID_CURSOR | 커서 값이 올바르지 않습니다. |
| 400 | INVALID_PAGE_SIZE | 페이지 크기가 올바르지 않습니다. |
| 400 | INVALID_MEETING_KEYWORD | 검색어가 올바르지 않습니다. |
| 500 | MEETING_CREATE_FAILED | 회의 생성에 실패했습니다. |

### TeamErrorCode

| HTTP | Code | Message |
|---|---|---|
| 404 | USER_NOT_FOUND | 사용자를 찾을 수 없습니다. |
| 404 | TEAM_NOT_FOUND | 팀을 찾을 수 없습니다. |
| 403 | TEAM_ACCESS_DENIED | 팀에 접근할 권한이 없습니다. |
| 409 | ACTIVE_TEAM_ALREADY_EXISTS | 이미 참여 중인 팀이 있습니다. |
| 500 | TEAM_CREATE_FAILED | 팀을 생성하지 못했습니다. 다시 시도해주세요. |

### UserErrorCode

| HTTP | Code | Message |
|---|---|---|
| 500 | USER_DELETE_FAILED | 회원 탈퇴에 실패했습니다. |

### CommonErrorCode

| HTTP | Code | Message |
|---|---|---|
| 400 | INVALID_INPUT_VALUE | 입력값이 올바르지 않습니다. |
| 500 | INTERNAL_SERVER_ERROR | 서버 오류가 발생했습니다. |

