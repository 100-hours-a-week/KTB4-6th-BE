package com.backend.meety.domain.ai.messaging;

import com.backend.meety.domain.ai.client.AiRequestInput;
import com.backend.meety.domain.ai.entity.AiRequestType;

public record AiRequestMessage(
        int schemaVersion,
        Long aiRequestId,
        String processingToken,
        AiRequestType requestType,
        AiRequestInput input
) {

    public static final int SCHEMA_VERSION = 1;

    public static AiRequestMessage create(Long aiRequestId, String processingToken, AiRequestInput input) {
        return new AiRequestMessage(SCHEMA_VERSION, aiRequestId, processingToken, input.requestType(), input);
    }
}
