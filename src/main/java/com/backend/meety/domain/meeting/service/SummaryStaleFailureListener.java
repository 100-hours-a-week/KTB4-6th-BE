package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.event.StaleAiRequestFailedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SummaryStaleFailureListener {

    private final SummaryProcessingService summaryProcessingService;

    @EventListener
    public void restoreCredit(StaleAiRequestFailedEvent event) {
        if (event.requestType() == AiRequestType.SUMMARY) {
            summaryProcessingService.restoreCreditAfterStaleFailure(event.aiRequestId());
        }
    }
}
