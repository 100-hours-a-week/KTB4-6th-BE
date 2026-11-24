package com.backend.meety.domain.ai.messaging;

import com.backend.meety.domain.ai.entity.AiRequestType;
import tools.jackson.databind.JsonNode;

public interface AiResultHandler {

    AiRequestType requestType();

    boolean handleSucceeded(Long aiRequestId, JsonNode result);

    void handleFinalFailure(Long aiRequestId);

    default long maxRetryCount() {
        return AiResponseRetryPolicy.MAX_RETRY_COUNT;
    }
}
