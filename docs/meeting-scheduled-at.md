# #303 회의 생성 예정 시작 일시

구현 기준: `origin/dev` `4a7979351d091cb9c40beafb29a3033e4d60a276`.
최신 dev의 #346 스케줄러 분리 변경은 병합해 반영한다.

## API

기존 `POST /api/v1/teams/{teamId}/meetings`에 필수 `scheduledAt`을 추가한다.
기존 인증, 참가자 지정, 응답 래퍼 및 목표 시간 필드명은 유지한다.
예시의 목표 시간 필드는 기존 계약인 `targetDurationMinutes`를 사용한다.

```json
{
  "title": "주간 회의",
  "purpose": "이번 주 업무 공유",
  "note": "필요 시 작성",
  "targetDurationMinutes": 30,
  "attendeeTeamMemberIds": [],
  "customAttendeeNames": ["외부 참석자"],
  "scheduledAt": "2026-10-22T15:00:00"
}
```

- `scheduledAt`: 오프셋 없는 KST LocalDateTime. 위 입력은 15:00 그대로 기존 `scheduled_at`에 저장한다.
- 기존에는 서버 현재 시각을 넣었지만 이제 요청값을 Entity factory에 전달한다.
- `createdAt`: 기존 JPA auditing이 기록하는 실제 생성 시각. 예정 시각으로 대체하지 않는다.
- 누락/null은 기존 Bean Validation, 잘못된 일시 문자열은 기존 JSON 파싱 예외 처리로
  HTTP 400 `INVALID_INPUT_VALUE`를 반환한다.
- 테이블/컬럼/시간대 정책 migration은 없다.

## 일일 제한 및 동시성

최신 dev의 기존 기본값/오류 문구/테스트 정책은 5개였다. 이번 확정 정책에 맞춰 20개로 변경한다.
local/staging/prod의 `meeting.daily-create-limit` 기본값은 20이다.
기존 `MEETING_DAILY_CREATE_LIMIT` 환경 변수 override는 유지한다.
배포 환경에서 변수가 명시적으로 5 등으로 설정돼 있다면 20으로 맞춰야 한다.

기존 transaction과 Team PESSIMISTIC_WRITE 잠금을 유지한다.
Team 잠금 → 생성 횟수 조회 → 생성/참석자 저장 → commit 순서다.
횟수 조회는 다음과 같으며 예정 시각/삭제 여부/상태를 조건으로 사용하지 않는다.

```sql
where team_id = :teamId
  and created_at >= :startOfDay
  and created_at < :nextDay
```

KST 오늘 날짜 계산은 기존 주입 Clock을 사용하도록 맞춘다.
서버의 실제 Clock에서는 동일한 KST 생성 당일 정책이며 월/연도 경계를 고정 Clock으로 검증한다.

## 목록 및 #342 경계

- 기존 WAITING의 `scheduledAt`, IN_PROGRESS/COMPLETED의 `startedAt` 기준을 유지한다.
- 목록은 날짜 내 유효 시작 시각 오름차순, 동률 ID 오름차순이다. 날짜 그룹은 기존 내림차순이다.
- 예정 날짜를 사용자가 고를 수 있으므로 WAITING의 날짜 그룹과 순서에는 입력값이 반영된다.
- #342에서 `now <= scheduledAt < now + 1 minute` 조회에 사용할 사용자 예정 시각이 저장된다.
- #342 Scheduler/AI prewarm 호출/실제 offset 변환/조회 최적화는 구현하지 않았다.
- Redis/AI/녹음 lifecycle 코드는 변경하지 않는다.

## 미확정 정책

생성 시 과거 금지, 최소 사전 예약 시간, 최대 예약 기간, 동일 시각 제한은 추가하지 않는다.
기존 수정 DTO에는 `@Future` 검증이 있다. 이번에는 수정 API를 변경하지 않으므로
생성/수정의 과거 시각 허용 정책 통일은 별도 정책 확정이 필요하다.

## 검증

- DTO/Controller: 필수값·일시 형식 오류·기존 문자열/목표시간 validation, LocalDateTime 전달.
- Service: 요청 시각 저장, Team 잠금/생성 횟수 확인 순서, 20개 경계, KST 월/연도 경계.
- 전용 MySQL: 예정/생성 시각 분리, 예정 시각 범위 조회, 서로 다른 20개 예정 날짜,
  삭제·완료·진행 상태 포함, 생성 자정 범위, 동시 생성, 상태별 목록 정렬.
- 기존 다른 작업 DB를 사용하지 않고 명시적 `MEETING_TEST_DB_URL`의 로컬
  `meeting_test_303`에서만 통합 테스트를 수행한다.

실행 결과:

| 범위 | 전체 | 통과 | 실패 | 오류 | Skip |
|---|---:|---:|---:|---:|---:|
| 생성 DTO/Controller/Service 및 MySQL 선별 | 100 | 100 | 0 | 0 | 0 |
| 회의 도메인 전체 (실제 Redis 포함) | 287 | 287 | 0 | 0 | 0 |
| 프로젝트 전체 (실제 MySQL/Redis 포함) | 1,294 | 1,292 | 2 | 0 | 0 |
| 깨끗한 기준 dev의 실패 2건 대조 실행 | 2 | 0 | 2 | 0 | 0 |

추가 테스트는 parameterized/repeated 실행 기준 17건이다. MySQL 동시 생성은 3회 모두
19개에서 8개 요청을 경쟁시켜 성공 1건/제한 거절 7건, 최종 20개를 확인했다.
생성 DTO/Controller/Service 기존 테스트에는 필수 예정 시각 fixture와 20개 경계를 반영했다.

전체 실패는 모두 `RecordingMySqlIntegrationTest`의 기존 실패다.

- `startCommitsSessionCreditLedgerAndMeetingTogether`: 원장 amount 기대 +20 / 실제 -20.
- `twoMeetingsInSameTeamCannotStartTogether`: 녹음 시작 시 team_credits 잠금 deadlock.

변경 없는 기준 dev `4a79793`의 별도 detached worktree에서도 동일하게 재현했다.
관련 없는 녹음/크레딧 코드나 기존 실패 assertion은 수정하지 않았다.
