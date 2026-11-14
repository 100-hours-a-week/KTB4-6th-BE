package com.backend.meety.domain.ai.messaging;

import com.backend.meety.domain.ai.entity.AiRequestType;

public record AiRequestMessage(
        int schemaVersion,
        Long aiRequestId,
        String processingToken,
        AiRequestType requestType,
        Object input
) {

    public static final int SCHEMA_VERSION = 1;

    public static AiRequestMessage create(
            Long aiRequestId, String processingToken, AiRequestType requestType, Object input) {
        return new AiRequestMessage(SCHEMA_VERSION, aiRequestId, processingToken, requestType, input);
    }
}
