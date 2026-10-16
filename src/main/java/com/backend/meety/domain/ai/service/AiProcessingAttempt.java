package com.backend.meety.domain.ai.service;

public record AiProcessingAttempt<T>(
        Long aiRequestId,
        String processingToken,
        T payload
) {
}
