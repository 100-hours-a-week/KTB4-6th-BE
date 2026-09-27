package com.backend.meety.domain.ai.client;

import java.util.List;

public record DiarizationAiRequest(
        String requestId,
        Long meetingId,
        String audioUrl,
        List<DiarizationAiSegment> segments
) {
}
