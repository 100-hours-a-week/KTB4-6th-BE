package com.backend.meety.domain.ai.messaging;

import com.backend.meety.domain.ai.entity.AiRequest;

public record AiRequestOutboxRecord(
        Long aiRequestId,
        String processingToken,
        String routingKey,
        String messagePayload
) {

    public static AiRequestOutboxRecord create(AiRequest aiRequest) {
        return new AiRequestOutboxRecord(
                aiRequest.getId(),
                aiRequest.getProcessingToken(),
                aiRequest.getPublishRoutingKey(),
                aiRequest.getMessagePayload());
    }
}
