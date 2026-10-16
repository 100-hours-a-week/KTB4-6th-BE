package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.client.MetricAiClient;
import com.backend.meety.domain.ai.client.MetricAiRequest;
import com.backend.meety.domain.ai.client.MetricAiResponse;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.ai.service.AiProcessingAttempt;
import com.backend.meety.domain.meeting.MetricPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

@Slf4j
@Component
@RequiredArgsConstructor
public class MetricProcessingScheduler {

    private final AiRequestRepository aiRequestRepository;
    private final MetricProcessingService metricProcessingService;
    private final MetricAiClient metricAiClient;

    @Scheduled(fixedDelay = MetricPolicy.SCHEDULER_POLL_DELAY_MILLIS)
    public void processAcceptedMetrics() {
        List<AiRequest> targets = aiRequestRepository.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.METRIC, AiRequestStatus.ACCEPTED, Limit.of(MetricPolicy.SCHEDULER_BATCH_SIZE));
        for (AiRequest target : targets) {
            processOne(target.getId());
        }
    }

    private void processOne(Long aiRequestId) {
        AiProcessingAttempt<MetricAiRequest> attempt =
                metricProcessingService.startProcessing(aiRequestId).orElse(null);
        if (attempt == null) {
            return;
        }
        MetricAiResponse response;
        try {
            response = metricAiClient.requestMetric(attempt.payload());
        } catch (Exception e) {
            log.warn("AI Metric 호출에 실패했습니다. aiRequestId={}", aiRequestId, e);
            if (isRetryable(e)) {
                metricProcessingService.handleRetryableFailure(aiRequestId, attempt.processingToken());
            } else {
                metricProcessingService.handlePermanentFailure(aiRequestId, attempt.processingToken());
            }
            return;
        }
        metricProcessingService.completeProcessing(aiRequestId, attempt.processingToken(), response);
    }

    private boolean isRetryable(Exception e) {
        if (e instanceof HttpClientErrorException clientError) {
            return clientError.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
        }
        return true;
    }
}
