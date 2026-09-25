package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.client.SummaryAiClient;
import com.backend.meety.domain.ai.client.SummaryAiRequest;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.meeting.SummaryPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryProcessingScheduler {

    private final AiRequestRepository aiRequestRepository;
    private final SummaryProcessingService summaryProcessingService;
    private final SummaryAiClient summaryAiClient;

    @Scheduled(fixedDelay = SummaryPolicy.SCHEDULER_POLL_DELAY_MILLIS)
    public void processAcceptedSummaries() {
        List<AiRequest> targets = aiRequestRepository.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.SUMMARY, AiRequestStatus.ACCEPTED, Limit.of(SummaryPolicy.SCHEDULER_BATCH_SIZE));
        for (AiRequest target : targets) {
            processOne(target.getId());
        }
    }

    private void processOne(Long aiRequestId) {
        SummaryAiRequest request = summaryProcessingService.startProcessing(aiRequestId).orElse(null);
        if (request == null) {
            return;
        }
        String content;
        try {
            content = summaryAiClient.requestSummary(request);
        } catch (Exception e) {
            log.warn("AI 요약 호출에 실패했습니다. aiRequestId={}", aiRequestId, e);
            if (isRetryable(e)) {
                summaryProcessingService.handleRetryableFailure(aiRequestId);
            } else {
                summaryProcessingService.handlePermanentFailure(aiRequestId);
            }
            return;
        }
        summaryProcessingService.completeProcessing(aiRequestId, content);
    }


    private boolean isRetryable(Exception e) {
        if (e instanceof HttpClientErrorException clientError) {
            return clientError.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
        }
        return true;
    }
}
