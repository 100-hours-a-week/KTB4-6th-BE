package com.backend.meety.domain.meeting.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeetingCreateRequestTest {

    private static final LocalDateTime SCHEDULED_AT = LocalDateTime.of(2026, 10, 22, 15, 0);
    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("회의 생성 요청은 목표 시간을 1분부터 60분까지 허용한다")
    void validateTargetDurationRange() {
        // 테스트 목적:
        // 예정 시작 일시를 추가해도 기존 목표 시간 경계 검증이 유지되는지 확인한다.
        // given
        var minimum = createRequest(1);
        var maximum = createRequest(60);
        var belowMinimum = createRequest(0);
        var aboveMaximum = createRequest(61);
        // when
        var minimumViolations = validator.validate(minimum);
        var maximumViolations = validator.validate(maximum);
        var belowViolations = validator.validate(belowMinimum);
        var aboveViolations = validator.validate(aboveMaximum);
        // then
        assertThat(minimumViolations).isEmpty();
        assertThat(maximumViolations).isEmpty();
        assertThat(belowViolations).isNotEmpty();
        assertThat(aboveViolations).isNotEmpty();
    }

    @Test
    @DisplayName("회의 생성 요청은 필수값과 문자열 길이를 검증한다")
    void validateRequiredFieldsAndTextLength() {
        // 테스트 목적:
        // 기존 필수값과 문자열 길이 및 새 예정 시작 일시 필수 검증을 확인한다.
        // given
        MeetingCreateRequest request = new MeetingCreateRequest(
                "",
                "a".repeat(101),
                "a".repeat(201),
                null,
                List.of(),
                List.of(),
                null
        );

        // when
        Set<ConstraintViolation<MeetingCreateRequest>> violations = validator.validate(request);

        // then
        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("title", "purpose", "note", "targetDurationMinutes", "scheduledAt");
    }

    @Test
    @DisplayName("회의 생성 요청은 제목을 2자부터 20자까지 허용한다")
    void validateTitleLength() {
        // 테스트 목적:
        // 예정 시각 입력이 기존 제목 길이 검증을 변경하지 않는지 확인한다.
        // given
        var minimum = createRequestWithTitle("주간");
        var maximum = createRequestWithTitle("a".repeat(20));
        var belowMinimum = createRequestWithTitle("A");
        var aboveMaximum = createRequestWithTitle("a".repeat(21));
        // when
        var minimumViolations = validator.validate(minimum);
        var maximumViolations = validator.validate(maximum);
        var belowViolations = validator.validate(belowMinimum);
        var aboveViolations = validator.validate(aboveMaximum);
        // then
        assertThat(minimumViolations).isEmpty();
        assertThat(maximumViolations).isEmpty();

        assertThat(belowViolations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("title");
        assertThat(aboveViolations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactly("title");
    }

    @Test
    @DisplayName("예정 시작 일시가 null이면 해당 필드만 필수값 오류로 거절한다")
    void scheduledAtIsRequired() {
        // 테스트 목적:
        // 다른 입력이 정상이더라도 예정 시작 일시가 없으면 생성할 수 없는지 검증한다.
        // given
        var request = new MeetingCreateRequest("주간 회의", "업무 공유", null, 30, List.of(), List.of(), null);
        // when
        var violations = validator.validate(request);
        // then
        assertThat(violations).extracting(v -> v.getPropertyPath().toString()).containsExactly("scheduledAt");
    }

    @Test
    @DisplayName("과거와 먼 미래의 예정 시각은 임의의 예약 기간 제한으로 거절하지 않는다")
    void noUnspecifiedReservationRestriction() {
        // 테스트 목적:
        // 확정되지 않은 과거 금지와 최대 예약 기간 정책을 추가하지 않는지 검증한다.
        // given
        var past = new MeetingCreateRequest("주간 회의", "업무 공유", null, 30,
                List.of(), List.of(), LocalDateTime.of(2000, 1, 1, 0, 0));
        var future = new MeetingCreateRequest("주간 회의", "업무 공유", null, 30,
                List.of(), List.of(), LocalDateTime.of(2100, 1, 1, 0, 0));
        // when
        var pastViolations = validator.validate(past);
        var futureViolations = validator.validate(future);
        // then
        assertThat(pastViolations).isEmpty();
        assertThat(futureViolations).isEmpty();
    }

    private MeetingCreateRequest createRequestWithTitle(String title) {
        return new MeetingCreateRequest(
                title, "진행 상황과 이슈 공유", "API 명세 검토", 30, List.of(), List.of(), SCHEDULED_AT);
    }

    private MeetingCreateRequest createRequest(Integer targetDurationMinutes) {
        return new MeetingCreateRequest(
                "주간 스프린트 회의",
                "진행 상황과 이슈 공유",
                "API 명세 검토",
                targetDurationMinutes,
                List.of(),
                List.of(),
                SCHEDULED_AT
        );
    }
}
