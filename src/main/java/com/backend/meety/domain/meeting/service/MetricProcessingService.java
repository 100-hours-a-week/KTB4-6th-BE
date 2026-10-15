package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.client.MetricAiAgenda;
import com.backend.meety.domain.ai.client.MetricAiOffTopic;
import com.backend.meety.domain.ai.client.MetricAiRequest;
import com.backend.meety.domain.ai.client.MetricAiResponse;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.meeting.MetricPolicy;
import com.backend.meety.domain.meeting.entity.MeetingMetric;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.meeting.repository.MeetingMetricRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetricProcessingService {

    private final AiRequestRepository aiRequestRepository;
    private final MeetingSummaryRepository meetingSummaryRepository;
    private final MeetingMetricRepository meetingMetricRepository;
    private final MetricAiRequestFactory metricAiRequestFactory;
    private final ObjectMapper objectMapper;

    @Transactional
    public Optional<MetricAiRequest> startProcessing(Long aiRequestId) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElse(null);
        if (aiRequest == null || !aiRequest.isAccepted()) {
            return Optional.empty();
        }
        MeetingSummary summary = findSummaryFor(aiRequest).orElse(null);
        if (summary == null || summary.getContent() == null) {
            log.error("Metric 처리 대상 요약을 찾을 수 없습니다. aiRequestId={}", aiRequestId);
            aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
            return Optional.empty();
        }
        if (aiRequestRepository.claimAccepted(aiRequestId) == 0) {
            return Optional.empty();
        }

        AiRequest claimedRequest = aiRequestRepository.findById(aiRequestId).orElse(null);
        if (claimedRequest == null) {
            return Optional.empty();
        }
        MeetingSummary claimedSummary = findSummaryFor(claimedRequest).orElse(null);
        if (claimedSummary == null || claimedSummary.getContent() == null) {
            log.error("Metric 처리 대상 요약을 찾을 수 없습니다. aiRequestId={}", aiRequestId);
            claimedRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
            return Optional.empty();
        }
        return Optional.of(metricAiRequestFactory.create(claimedRequest, claimedSummary));
    }

    @Transactional
    public void completeProcessing(Long aiRequestId, MetricAiResponse response) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElseThrow();
        MeetingSummary summary = findSummaryFor(aiRequest).orElse(null);
        if (summary == null || !isValidResponse(aiRequest, summary, response)) {
            aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
            return;
        }
        if (meetingMetricRepository.existsByAiRequestId(aiRequestId)) {
            aiRequest.markCompleted();
            log.info("이미 저장된 Metric 응답을 건너뜁니다. aiRequestId={}", aiRequestId);
            return;
        }

        MeetingMetric metric = MeetingMetric.create(
                aiRequest,
                summary.getMeeting(),
                metricsJson(response.agendas()),
                contextOutDurationMs(response.offTopic())
        );
        try {
            meetingMetricRepository.save(metric);
        } catch (DataIntegrityViolationException e) {
            log.warn("Metric 응답이 중복 저장되어 건너뜁니다. aiRequestId={}", aiRequestId, e);
        }
        aiRequest.markCompleted();
        log.info("회의 Metric 저장이 완료되었습니다. aiRequestId={}, meetingId={}, summaryId={}",
                aiRequestId, summary.getMeeting().getId(), summary.getId());
    }

    @Transactional
    public void handleRetryableFailure(Long aiRequestId) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElseThrow();
        aiRequest.increaseRetryCount();
        if (aiRequest.getRetryCount() < MetricPolicy.MAX_RETRY_COUNT) {
            aiRequest.markAccepted();
            log.warn("AI Metric 호출에 실패해 재시도합니다. aiRequestId={}, retryCount={}",
                    aiRequestId, aiRequest.getRetryCount());
            return;
        }
        failPermanently(aiRequest);
    }

    @Transactional
    public void handlePermanentFailure(Long aiRequestId) {
        AiRequest aiRequest = aiRequestRepository.findById(aiRequestId).orElseThrow();
        failPermanently(aiRequest);
    }

    private Optional<MeetingSummary> findSummaryFor(AiRequest aiRequest) {
        Long summaryId;
        try {
            summaryId = MetricPolicy.summaryIdOf(aiRequest.getIdempotencyKey());
        } catch (RuntimeException e) {
            log.error("Metric 요청 멱등키가 올바르지 않습니다. aiRequestId={}, idempotencyKey={}",
                    aiRequest.getId(), aiRequest.getIdempotencyKey(), e);
            return Optional.empty();
        }
        return meetingSummaryRepository.findById(summaryId);
    }

    private boolean isValidResponse(AiRequest aiRequest, MeetingSummary summary, MetricAiResponse response) {
        if (response == null) {
            log.error("AI Metric 응답이 비어 있습니다. aiRequestId={}", aiRequest.getId());
            return false;
        }
        boolean valid = String.valueOf(aiRequest.getId()).equals(response.requestId())
                && summary.getMeeting().getId().equals(response.meetingId());
        if (!valid) {
            log.error("AI Metric 응답이 요청과 일치하지 않습니다. aiRequestId={}, responseRequestId={}, "
                            + "meetingId={}, responseMeetingId={}",
                    aiRequest.getId(), response.requestId(), summary.getMeeting().getId(), response.meetingId());
        }
        return valid;
    }

    private String metricsJson(List<MetricAiAgenda> agendas) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("agendas", agendas);
        return objectMapper.writeValueAsString(payload);
    }

    private Long contextOutDurationMs(MetricAiOffTopic offTopic) {
        return offTopic != null ? offTopic.durationMs() : null;
    }

    private void failPermanently(AiRequest aiRequest) {
        aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
        log.error("AI Metric 호출이 최종 실패했습니다. aiRequestId={}, retryCount={}",
                aiRequest.getId(), aiRequest.getRetryCount());
    }

}
