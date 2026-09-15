package com.backend.meety.domain.meeting.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDateTime;

public record MeetingCreateRequest(
        @NotBlank(message = "회의 제목을 입력해주세요.")
        @Size(max = 20, message = "회의 제목은 최대 20자까지 입력할 수 있습니다.")
        String title,

        @NotBlank(message = "회의 목적을 입력해주세요.")
        @Size(max = 100, message = "회의 목적은 최대 100자까지 입력할 수 있습니다.")
        String purpose,

        @Size(max = 200, message = "회의 메모는 최대 200자까지 입력할 수 있습니다.")
        String note,

        @NotNull(message = "회의 예정 시간을 입력해주세요.")
        LocalDateTime scheduledAt,

        @NotNull(message = "회의 목표 시간을 입력해주세요.")
        @Min(value = 1, message = "회의 목표 시간은 1분 이상이어야 합니다.")
        @Max(value = 60, message = "회의 목표 시간은 60분 이하이어야 합니다.")
        Integer targetDurationMinutes
) {
}
