package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.AiRequestRecoveryProperties;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.event.StaleAiRequestFailedEvent;
import com.backend.meety.domain.ai.repository.AiRequestRecoveryCandidate;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.meeting.MetricPolicy;
import com.backend.meety.domain.meeting.SummaryPolicy;
import com.backend.meety.domain.transcript.DiarizationPolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiRequestRecoveryService {

    private static final List<AiRequestType> RECOVERY_TYPES = List.of(
            AiRequestType.SUMMARY,
            AiRequestType.DIARIZATION,
            AiRequestType.METRIC
    );

    private final AiRequestRepository aiRequestRepository;
    private final AiRequestRecoveryProperties properties;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public AiRequestRecoveryResult recoverStaleProcessing() {
        LocalDateTime staleBefore = LocalDateTime.now(clock).minus(properties.processingTimeout());
        List<AiRequestRecoveryCandidate> candidates = aiRequestRepository.findStaleProcessingCandidates(
                RECOVERY_TYPES, staleBefore, Limit.of(properties.batchSize()));
        int recovered = 0;
        int failed = 0;
        int missed = 0;

        for (AiRequestRecoveryCandidate candidate : candidates) {
            long maxRetryCount = maxRetryCount(candidate.requestType());
            long lastRetryCount = maxRetryCount - 1;
            if (aiRequestRepository.recoverStaleProcessing(
                    candidate.id(), staleBefore, lastRetryCount) == 1) {
                recovered++;
                continue;
            }
            if (aiRequestRepository.failStaleProcessing(
                    candidate.id(), staleBefore, lastRetryCount, maxRetryCount) == 1) {
                failed++;
                eventPublisher.publishEvent(new StaleAiRequestFailedEvent(
                        candidate.id(), candidate.requestType()));
                continue;
            }
            missed++;
        }

        AiRequestRecoveryResult result = new AiRequestRecoveryResult(
                candidates.size(), recovered, failed, missed);
        if (!candidates.isEmpty()) {
            log.info("stale AI 요청 복구를 완료했습니다. attempts={}, recovered={}, failed={}, missed={}, staleBefore={}",
                    result.attempts(), result.recovered(), result.failed(), result.missed(), staleBefore);
        }
        return result;
    }

    private long maxRetryCount(AiRequestType requestType) {
        return switch (requestType) {
            case SUMMARY -> SummaryPolicy.MAX_RETRY_COUNT;
            case DIARIZATION -> DiarizationPolicy.MAX_RETRY_COUNT;
            case METRIC -> MetricPolicy.MAX_RETRY_COUNT;
            default -> throw new IllegalArgumentException("Unsupported recovery request type: " + requestType);
        };
    }
}
