package com.backend.meety.domain.ai.event;

import com.backend.meety.domain.ai.entity.AiRequestType;

public record StaleAiRequestFailedEvent(
        Long aiRequestId,
        AiRequestType requestType
) {
}
