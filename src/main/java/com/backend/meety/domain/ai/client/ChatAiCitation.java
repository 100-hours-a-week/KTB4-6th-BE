package com.backend.meety.domain.ai.client;

public record ChatAiCitation(
        String sourceType,
        Long meetingId,
        Long segmentId,
        Long summaryId
) {
}
