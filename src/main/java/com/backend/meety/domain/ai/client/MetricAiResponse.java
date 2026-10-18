package com.backend.meety.domain.ai.client;

import java.util.List;

public record MetricAiResponse(
        String requestId,
        Long meetingId,
        MetricAiOffTopic offTopic,
        List<MetricAiAgenda> agendas
) {
}
