# ADR-0001: Redis 장애 후 녹음 STOP 상태 보정

## Status

Accepted — 구현은 [PR #380](https://github.com/100-hours-a-week/KTB4-6th-BE/pull/380)으로 2026-10-28 `dev`에 병합됨.

---

## Context

녹음 종료 요청은 MySQL에서 RecordingSession과 Meeting을 `COMPLETED`로 변경한 뒤, 커밋 후 Redis Owner를 조회하고 Pub/Sub으로 AI WebSocket 연결 소유 BE에 STOP 명령을 전달한다. Redis Owner 조회 또는 Pub/Sub 발행이 실패해도 이미 커밋된 DB 종료 상태는 되돌아가지 않는다. 이때 MySQL은 `COMPLETED`인데 AI 세션은 `READY`로 남을 수 있다. Redis가 복구돼도 유실된 Pub/Sub 명령은 재생되지 않는다.

문제 흐름과 원본 장애 실험은 [Issue #378](https://github.com/100-hours-a-week/KTB4-6th-BE/issues/378), 복구 구현은 [Issue #379](https://github.com/100-hours-a-week/KTB4-6th-BE/issues/379)와 [PR #380](https://github.com/100-hours-a-week/KTB4-6th-BE/pull/380)에 기록했다. 선택한 구현은 AI 연결을 소유한 BE의 로컬 연결 목록을 대상으로 MySQL Primary 상태를 주기적으로 대조하는 방식이다.

현재 코드의 주요 경로:

- `RecordingService.updateStatus()`와 커밋 후 STOP 이벤트: [RecordingService.java](../../src/main/java/com/backend/meety/domain/recording/service/RecordingService.java#L159), [AiLiveMeetingStopListener.java](../../src/main/java/com/backend/meety/domain/ai/realtime/AiLiveMeetingStopListener.java#L17)
- 로컬 활성 연결 조회, DB 상태 확인, Owner 검증 및 보정: [RecordingStopReconciliationScheduler.java](../../src/main/java/com/backend/meety/domain/recording/owner/RecordingStopReconciliationScheduler.java#L24)
- Primary DB 상태 조회: [RecordingStatusPrimaryReader.java](../../src/main/java/com/backend/meety/domain/recording/service/RecordingStatusPrimaryReader.java#L11)
- 연결 단위 STOP과 전사 확정 중복 방지: [AiLiveMeetingConnectionService.java](../../src/main/java/com/backend/meety/domain/ai/realtime/AiLiveMeetingConnectionService.java#L42), [AiLiveMeetingConnection.java](../../src/main/java/com/backend/meety/domain/ai/realtime/AiLiveMeetingConnection.java#L169)
- 기본 설정과 운영 토글: [application.yaml](../../src/main/resources/application.yaml#L51)
- 재현 조건과 실측 결과: [recording-stop-reconciliation.md](../recording-stop-reconciliation.md)

### 확인된 제약과 가정

- BE 인스턴스의 AI ConnectionRegistry는 로컬 메모리이므로, 연결을 가진 인스턴스만 그 연결을 직접 종료할 수 있다.
- 녹음 상태의 기준 정보는 MySQL이다. 보정 조회는 읽기 복제본 지연을 피하도록 Primary 경로를 사용한다.
- Redis Owner가 사라지거나 다른 연결을 가리킬 때 로컬 연결만 보고 종료하면 현재 연결 소유자를 잘못 판단할 수 있다. 그래서 보정은 Owner가 로컬 연결과 일치할 때만 진행한다.
- 전역 소유권 확인은 Redis를 이용하므로 Redis를 사용할 수 없는 동안에는 안전한 보정도 지연될 수 있다.
- 검증 결과는 로컬 두 BE, Mock/Fake AI, 모의 FE 조건이며 실제 브라우저와 외부 AI 성능을 나타내지 않는다.

---

## Decision Drivers

이 결정은 Issue #379의 요구와 운영 제약을 기준으로 내렸다.

- MySQL 종료 상태와 AI WebSocket의 상태 불일치를 자동으로 발견하고 정리한다.
- 현재 BE가 보유한 연결과 DB 상태를 재사용해 구현 및 운영 복잡도를 낮춘다.
- 잘못된 BE가 새 Owner의 AI 연결을 종료하지 않도록 연결 ID와 Owner를 검증한다.
- DB 트랜잭션이나 행 잠금을 잡은 채 Redis 또는 AI 응답을 기다리지 않는다.
- 운영에서 기능 활성화와 조회 주기를 설정으로 통제한다.
- 장애 재현에서 정상 STOP, AI 종료 응답, 연결 정리 여부를 구분해 검증한다.

---

## Considered Options

### 1. 로컬 AI 연결 기반 MySQL Primary Polling

각 BE가 자기 `AiLiveMeetingConnectionRegistry`의 종료되지 않은 연결만 모아 해당 세션들의 DB 상태를 묶음 조회한다. `COMPLETED` 상태가 발견되면 Redis Owner와 로컬 연결 Owner가 같은지 확인한 뒤 기존 AI STOP 및 전사 확정 흐름을 재사용한다.

**장점**

- 활성 연결만 조회하므로 전체 녹음 세션을 반복 스캔하지 않는다.
- DB에 이미 저장된 최종 상태를 기준으로 하며 Redis 메시지 발행이 실패해도 DB 확인 자체는 가능하다.
- 새 브로커나 서비스 간 주소 등록 체계가 필요 없다.
- 세션당 중복 STOP 방지와 전사 확정 경로를 기존 Connection 상태 및 dispatcher와 연결할 수 있다.

**단점**

- BE 인스턴스별 Primary 조회가 주기적으로 발생한다. 실제 조회 비용은 동시 연결 수와 주기별 부하 테스트가 더 필요하다.
- Owner 확인에 Redis가 필요하므로 Owner Key 소실이나 Redis 장애 중에는 보정을 진행할 수 없다.
- Redis Owner 확인 후 전역 소유자가 교체되는 짧은 시간차를 원자적으로 막는 분산 fencing은 제공하지 않는다.
- 폴링 주기만큼 감지와 보정에 지연이 생긴다.

### 2. DB Outbox와 Worker

녹음 상태 변경과 같은 DB 트랜잭션에서 STOP 처리 항목을 Outbox 테이블에 기록하고 Worker가 전달을 재시도한다. 명령 유실을 DB에 남길 수 있다.

**장점**

- 상태 변경과 전달할 작업이 같은 트랜잭션에 남아 재처리 근거가 분명하다.
- 전달 실패와 재시도 상태를 영속 데이터로 관찰할 수 있다.

**단점**

- Worker가 어느 BE의 로컬 WebSocket 연결에 STOP을 전달할지 별도 라우팅 계약이 필요하다. Redis를 사용할 수 없는 동안 소유 인스턴스를 알아내는 문제는 남는다.
- Outbox 스키마, claim/lease, 재시도, 중복 소비, 보관·정리 정책까지 새로 운영해야 한다.
- 현재 이슈의 복구 범위에 비해 구성과 운영 부담이 크다.

### 3. BE 간 직접 HTTP 전달

요청을 처리한 BE가 AI 연결을 보유한 BE의 내부 HTTP API를 호출해 STOP을 전달한다.

**장점**

- Redis Pub/Sub 없이 소유 BE와 직접 통신할 수 있다.
- 성공/실패 응답을 호출자에게 돌려줄 수 있다.

**단점**

- Redis 장애 중 소유 BE의 주소를 찾는 별도 서비스 디스커버리 정보가 필요하다.
- 주소와 연결이 동시에 교체될 때 잘못된 세션에 명령하지 않도록 세대·소유권 검증을 새로 설계해야 한다.
- 호출 BE 장애, 재시도, 중복 요청 문제도 해결해야 한다.

---

## Decision

**BE별 로컬 AI 연결을 기준으로 MySQL Primary 상태를 주기적으로 조회하고, DB가 `COMPLETED`이며 Redis Owner와 로컬 연결이 일치할 때 해당 연결에 STOP을 보정한다.**

이 방식은 이미 존재하는 DB 상태, 로컬 ConnectionRegistry, Redis Owner, AI STOP 및 전사 확정 경로를 이어서 추가 인프라 없이 불일치를 복구한다. 새 Worker나 BE 주소 관리가 필요한 Outbox·직접 HTTP보다 현재 Meety의 연결 소유 구조에 맞는다.

Owner가 없거나 읽을 수 없거나 로컬 연결과 다르면 보정을 보류한다. Redis Owner를 무조건 다시 설정하는 방식은 소유권 경쟁을 일으킬 수 있어 선택하지 않았다. 기능은 `RECORDING_STOP_RECONCILIATION_ENABLED` 설정으로 제어하며 기본값은 `false`, 기본 간격은 3초다. 운영 활성화와 최적 간격은 추가 검증 대상이다.

---

## Consequences

### Positive

- 정상 Pub/Sub이 유실되어도 소유 BE가 DB 종료 상태를 확인해 AI 세션 종료를 시작할 수 있다.
- 활성 연결만 세션 ID 목록으로 조회하고 한 번에 최대 100개 상태를 가져온다.
- Primary 상태 조회 트랜잭션을 끝낸 뒤 Redis와 AI I/O를 수행한다.
- 같은 연결에 대한 Pub/Sub STOP과 보정 STOP이 경합해도 한 번만 STOP을 전송하며 전사 확정 시작도 연결별로 한 번만 허용한다.
- 로컬 두 BE + Fake AI 장애 실험에서 Master 장애 상황은 DB 종료부터 AI `session.ended`까지 약 7.44초, BE B 단독 Redis 단절은 약 0.66초였다. 정상 STOP에서는 보정 없이 AI 종료가 1회 확인됐다. 이 수치는 해당 로컬 실행 조건에 한정된다.

### Negative

- Polling 조회 비용과 실제 AI 공급자에서의 STOP 응답 시간은 아직 충분히 부하 검증되지 않았다.
- Master 장애 중 보정은 Sentinel/Lettuce가 Redis Owner를 다시 읽을 수 있을 때까지 지연된다. Owner Key가 없으면 현재 구현은 자동 복구하지 않는다.
- Redis 확인과 STOP 송신 사이의 전역 Owner 교체를 원자적으로 차단하지 못한다. 연결의 Owner 재검증과 Recovery fence는 로컬 경합을 줄이지만, 분산 fencing과 동등하지 않다.
- 기본 설정은 비활성화되어 있어 운영 환경에서 사용하려면 환경별로 명시적인 활성화가 필요하다.
- Fake AI·모의 FE 검증은 실제 브라우저 연결, 외부 AI 동작, 동시 다량 녹음의 결과를 보장하지 않는다.

---

## Reconsideration Conditions

- 동시 녹음 부하 측정에서 Primary 조회가 DB 커넥션 대기 또는 쿼리 지연의 유의미한 원인으로 확인되면 Outbox/Worker 또는 조회 간격·대상 선택을 다시 비교한다. 허용 가능한 DB 비용과 지연 목표는 측정 전까지 미정이다.
- Owner 소실 상태에서도 종료 명령을 복구해야 한다는 요구가 생기면 현재 보수적 보류 정책을 재검토하고 세션 세대가 포함된 전역 fencing 또는 영속 제어 명령 저장 방식을 설계한다.
- BE 인스턴스가 Redis 없이도 서로를 안정적으로 발견하고 연결 세대를 검증할 수 있는 서비스 디스커버리 계약이 마련되면 직접 BE 통신을 재평가한다.
- 운영에서 AI `session.ended`가 오지 않는 사례가 확인되면 기존 종료 타임아웃 이후 전사 확정과 최종 데이터 정합성을 별도 결정으로 검토한다.
