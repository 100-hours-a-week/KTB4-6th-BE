package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.messaging.AiResultHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

@Component
@RequiredArgsConstructor
public class SummaryResultHandler implements AiResultHandler {

    private final SummaryProcessingService summaryProcessingService;

    @Override
    public AiRequestType requestType() {
        return AiRequestType.SUMMARY;
    }

    @Override
    public void handleSucceeded(Long aiRequestId, JsonNode result) {
        summaryProcessingService.completeSummary(aiRequestId, result.path("content").asString());
    }

    @Override
    public void handleFinalFailure(Long aiRequestId) {
        summaryProcessingService.restoreCreditIfFailed(aiRequestId);
    }
}
