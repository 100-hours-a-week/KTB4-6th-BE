package com.backend.meety.domain.ai.client;

import java.util.List;

public record SummaryAiRequest(
        String requestId,
        Long meetingId,
        String title,
        String purpose,
        String note,
        String meetingStartedAt,
        List<SummaryAiSpeaker> speakers,
        List<SummaryAiSegment> segments
) {
}
