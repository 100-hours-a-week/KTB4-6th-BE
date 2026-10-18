package com.backend.meety.domain.ai.dto;

import com.backend.meety.domain.meeting.dto.SummaryDetailResponse;

public record InternalSummaryResponse(
        SummaryDetailResponse summary
) {
}
