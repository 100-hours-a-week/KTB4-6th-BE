package com.backend.meety.domain.meeting.dto;

import jakarta.validation.constraints.Size;

public record SummaryRegenerateRequest(
        @Size(max = 100, message = "재생성 사유는 100자 이하여야 합니다.")
        String reason
) {
}
