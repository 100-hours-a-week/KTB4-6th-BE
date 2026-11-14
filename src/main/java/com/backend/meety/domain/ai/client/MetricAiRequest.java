package com.backend.meety.domain.ai.client;

import com.backend.meety.domain.ai.entity.AiRequestType;
import java.util.List;

public record MetricAiRequest(
        String requestId,
        Long meetingId,
        String summary,
        List<MetricAiSegment> segments
) implements AiRequestInput {

    @Override
    public AiRequestType requestType() {
        return AiRequestType.METRIC;
    }
}
