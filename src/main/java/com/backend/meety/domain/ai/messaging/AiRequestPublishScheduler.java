package com.backend.meety.domain.ai.messaging;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiRequestPublishScheduler {

    private final AiRequestPublishService publishService;
    private final AiRequestPublisher publisher;
    private final AiRequestPublishProperties properties;

    @Scheduled(fixedDelayString = "${ai.request.publish.poll-delay}")
    public void publishPending() {
        for (Long aiRequestId : publishService.findPublishableIds(properties.batchSize())) {
            try {
                publishService.claim(aiRequestId).ifPresent(this::publish);
            } catch (RuntimeException e) {
                log.error("AI 요청 발행 처리 중 오류가 발생했습니다. aiRequestId={}", aiRequestId, e);
            }
        }
    }

    private void publish(AiRequestOutboxRecord outboxRecord) {
        Long aiRequestId = outboxRecord.aiRequestId();
        String processingToken = outboxRecord.processingToken();
        switch (publisher.publish(outboxRecord)) {
            case CONFIRMED -> publishService.markPublished(aiRequestId, processingToken);
            case RETURNED -> {
                log.error("AI 요청 메시지를 라우팅할 Queue가 없습니다. aiRequestId={}, exchange={}, routingKey={}",
                        aiRequestId, AiRabbitTopology.REQUEST_EXCHANGE, outboxRecord.routingKey());
                publishService.revertToUnpublished(aiRequestId, processingToken);
            }
            case NACKED, FAILED -> {
                log.warn("AI 요청 메시지를 브로커에 전달하지 못했습니다. aiRequestId={}", aiRequestId);
                publishService.revertToUnpublished(aiRequestId, processingToken);
            }
            case UNCONFIRMED -> log.warn("AI 요청 메시지의 발행 확인을 받지 못했습니다. aiRequestId={}", aiRequestId);
        }
    }
}
