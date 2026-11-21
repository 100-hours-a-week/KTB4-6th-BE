package com.backend.meety.domain.ai.messaging;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@Slf4j
@Service
public class AiResponseService {

    private final AiRequestRepository aiRequestRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Map<AiRequestType, AiResultHandler> handlers;

    public AiResponseService(AiRequestRepository aiRequestRepository, ObjectMapper objectMapper, Clock clock,
                             List<AiResultHandler> handlers) {
        this.aiRequestRepository = aiRequestRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.handlers = handlers.stream()
                .collect(Collectors.toUnmodifiableMap(AiResultHandler::requestType, Function.identity()));
    }

    @Transactional
    public void handle(AiResponseMessage message) {
        AiResultHandler handler = handlers.get(message.requestType());
        if (handler == null) {
            throw new IllegalStateException("No AI result handler for " + message.requestType());
        }
        switch (message.eventType()) {
            case SUCCEEDED -> handleSucceeded(message, handler);
            case FAILED -> handleFailed(message, handler);
        }
    }

    private void handleSucceeded(AiResponseMessage message, AiResultHandler handler) {
        if (aiRequestRepository.completeIfCurrent(message.aiRequestId(), message.processingToken()) == 0) {
            log.info("이미 처리했거나 현재 실행이 아닌 AI 성공 응답을 무시합니다. aiRequestId={}", message.aiRequestId());
            return;
        }
        handler.handleSucceeded(message.aiRequestId(), message.result());
    }

    private void handleFailed(AiResponseMessage message, AiResultHandler handler) {
        AiRequest aiRequest = aiRequestRepository.findById(message.aiRequestId()).orElse(null);
        if (aiRequest == null || !aiRequest.isAccepted()
                || !message.processingToken().equals(aiRequest.getProcessingToken())) {
            log.info("이미 처리했거나 현재 실행이 아닌 AI 실패 응답을 무시합니다. aiRequestId={}", message.aiRequestId());
            return;
        }
        if (aiRequest.getRetryCount() < AiResponseRetryPolicy.LAST_RETRY_COUNT) {
            scheduleRetry(aiRequest, message);
            return;
        }
        if (aiRequestRepository.failIfRetryExhausted(aiRequest.getId(), message.processingToken(),
                AiResponseRetryPolicy.LAST_RETRY_COUNT, AiResponseRetryPolicy.MAX_RETRY_COUNT) == 1) {
            log.error("AI 작업이 최종 실패했습니다. aiRequestId={}, requestType={}, errorCode={}",
                    aiRequest.getId(), message.requestType(), message.error().code());
            handler.handleFinalFailure(aiRequest.getId());
        }
    }

    private void scheduleRetry(AiRequest aiRequest, AiResponseMessage message) {
        String nextProcessingToken = UUID.randomUUID().toString();
        ObjectNode nextPayload = (ObjectNode) objectMapper.readTree(aiRequest.getMessagePayload());
        nextPayload.put("processingToken", nextProcessingToken);
        LocalDateTime nextPublishAt = LocalDateTime.now(clock)
                .plus(AiResponseRetryPolicy.backoff(aiRequest.getRetryCount()));
        if (aiRequestRepository.scheduleRetry(aiRequest.getId(), message.processingToken(), nextProcessingToken,
                objectMapper.writeValueAsString(nextPayload), nextPublishAt,
                AiResponseRetryPolicy.LAST_RETRY_COUNT) == 1) {
            log.warn("AI 작업 실패로 재시도를 예약했습니다. aiRequestId={}, errorCode={}, retryCount={}, nextPublishAt={}",
                    aiRequest.getId(), message.error().code(), aiRequest.getRetryCount() + 1, nextPublishAt);
        }
    }
}
