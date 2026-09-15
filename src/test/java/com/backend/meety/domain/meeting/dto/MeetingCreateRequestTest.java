package com.backend.meety.domain.meeting.dto;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class MeetingCreateRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("회의 생성 요청은 목표 시간을 1분부터 60분까지 허용한다")
    void validateTargetDurationRange() {
        assertThat(validator.validate(createRequest(1))).isEmpty();
        assertThat(validator.validate(createRequest(60))).isEmpty();

        assertThat(validator.validate(createRequest(0))).isNotEmpty();
        assertThat(validator.validate(createRequest(61))).isNotEmpty();
    }

    @Test
    @DisplayName("회의 생성 요청은 필수값과 문자열 길이를 검증한다")
    void validateRequiredFieldsAndTextLength() {
        MeetingCreateRequest request = new MeetingCreateRequest(
                "",
                "a".repeat(101),
                "a".repeat(201),
                null,
                null
        );

        Set<ConstraintViolation<MeetingCreateRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("title", "purpose", "note", "scheduledAt", "targetDurationMinutes");
    }

    private MeetingCreateRequest createRequest(Integer targetDurationMinutes) {
        return new MeetingCreateRequest(
                "주간 스프린트 회의",
                "진행 상황과 이슈 공유",
                "API 명세 검토",
                LocalDateTime.of(2026, 9, 15, 15, 0),
                targetDurationMinutes
        );
    }
}
