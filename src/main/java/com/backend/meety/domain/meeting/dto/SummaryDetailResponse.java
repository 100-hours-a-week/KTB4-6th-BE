package com.backend.meety.domain.meeting.dto;

import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import java.time.LocalDateTime;

public record SummaryDetailResponse(
        Long summaryId,
        String content,
        Long version,
        AiRequestStatus status,
        AiFailureReason failureReason,
        LocalDateTime createdAt
) {

    public static SummaryDetailResponse from(MeetingSummary summary) {
        return new SummaryDetailResponse(
                summary.getId(),
                summary.getContent(),
                summary.getVersion(),
                summary.getAiRequest().getStatus(),
                summary.getAiRequest().getFailureReason(),
                summary.getCreatedAt()
        );
    }
}
