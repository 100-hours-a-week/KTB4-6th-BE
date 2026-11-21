package com.backend.meety.domain.ai.messaging;

import com.backend.meety.domain.ai.entity.AiRequestType;
import tools.jackson.databind.JsonNode;

public record AiResponseMessage(
        Integer schemaVersion,
        Long aiRequestId,
        String processingToken,
        AiRequestType requestType,
        AiResponseEventType eventType,
        JsonNode result,
        AiResponseError error
) {
}
