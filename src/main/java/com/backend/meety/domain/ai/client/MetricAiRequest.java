package com.backend.meety.domain.ai.client;

import java.util.List;

public record MetricAiRequest(
        String requestId,
        Long meetingId,
        String summary,
        List<MetricAiSegment> segments
) {
}
