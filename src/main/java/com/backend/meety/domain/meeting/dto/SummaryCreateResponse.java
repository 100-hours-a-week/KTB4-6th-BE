package com.backend.meety.domain.meeting.dto;

import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.meeting.entity.MeetingSummary;

public record SummaryCreateResponse(
        Long summaryId,
        Long version,
        AiRequestStatus status,
        Long creditBalance
) {

    public static SummaryCreateResponse of(MeetingSummary summary, long creditBalance) {
        return new SummaryCreateResponse(
                summary.getId(),
                summary.getVersion(),
                summary.getAiRequest().getStatus(),
                creditBalance
        );
    }
}
