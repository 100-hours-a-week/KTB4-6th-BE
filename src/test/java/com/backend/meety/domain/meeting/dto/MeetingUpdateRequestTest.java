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

class MeetingUpdateRequestTest {

    private Validator validator;

    @BeforeEach
    void setUp() {
        validator = Validation.buildDefaultValidatorFactory().getValidator();
    }

    @Test
    @DisplayName("회의 수정 요청은 null 필드를 허용한다")
    void validateNullFieldsAllowed() {
        MeetingUpdateRequest request = new MeetingUpdateRequest(null, null, null, null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("회의 수정 요청은 title과 purpose blank를 허용하지 않는다")
    void validateBlankTitleAndPurpose() {
        MeetingUpdateRequest request = new MeetingUpdateRequest("   ", "", null, null, null);

        Set<ConstraintViolation<MeetingUpdateRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("titleValid", "purposeValid");
    }

    @Test
    @DisplayName("회의 수정 요청은 문자열 길이를 검증한다")
    void validateTextLength() {
        MeetingUpdateRequest request = new MeetingUpdateRequest(
                "a".repeat(21),
                "a".repeat(101),
                "a".repeat(201),
                null,
                null
        );

        Set<ConstraintViolation<MeetingUpdateRequest>> violations = validator.validate(request);

        assertThat(violations)
                .extracting(violation -> violation.getPropertyPath().toString())
                .contains("title", "purpose", "note");
    }

    @Test
    @DisplayName("회의 수정 요청은 note 빈 문자열을 허용한다")
    void validateEmptyNoteAllowed() {
        MeetingUpdateRequest request = new MeetingUpdateRequest(null, null, "", null, null);

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    @DisplayName("회의 수정 요청은 목표 시간을 1분부터 60분까지 허용한다")
    void validateTargetDurationRange() {
        assertThat(validator.validate(requestWithDuration(1))).isEmpty();
        assertThat(validator.validate(requestWithDuration(60))).isEmpty();

        assertThat(validator.validate(requestWithDuration(0))).isNotEmpty();
        assertThat(validator.validate(requestWithDuration(61))).isNotEmpty();
    }

    @Test
    @DisplayName("회의 수정 요청은 과거 scheduledAt을 허용하지 않는다")
    void validateScheduledAtFuture() {
        MeetingUpdateRequest past = new MeetingUpdateRequest(
                null,
                null,
                null,
                LocalDateTime.now().minusDays(1),
                null
        );
        MeetingUpdateRequest future = new MeetingUpdateRequest(
                null,
                null,
                null,
                LocalDateTime.now().plusDays(1),
                null
        );

        assertThat(validator.validate(past)).isNotEmpty();
        assertThat(validator.validate(future)).isEmpty();
    }

    private MeetingUpdateRequest requestWithDuration(Integer targetDurationMinutes) {
        return new MeetingUpdateRequest(null, null, null, null, targetDurationMinutes);
    }
}
