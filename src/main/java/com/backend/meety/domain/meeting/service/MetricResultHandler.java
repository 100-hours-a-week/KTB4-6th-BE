package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.client.MetricAiResponse;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.messaging.AiResultHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricResultHandler implements AiResultHandler {

    private final MetricProcessingService metricProcessingService;
    private final ObjectMapper objectMapper;

    @Override
    public AiRequestType requestType() {
        return AiRequestType.METRIC;
    }

    @Override
    public boolean handleSucceeded(Long aiRequestId, JsonNode result) {
        MetricAiResponse response;
        try {
            response = objectMapper.treeToValue(result, MetricAiResponse.class);
        } catch (JacksonException e) {
            log.error("AI Metric 결과를 해석할 수 없습니다. aiRequestId={}", aiRequestId, e);
            return false;
        }
        return metricProcessingService.completeMetric(aiRequestId, response);
    }

    @Override
    public void handleFinalFailure(Long aiRequestId) {
    }
}
