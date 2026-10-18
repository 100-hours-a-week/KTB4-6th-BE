package com.backend.meety.domain.ai.service;

public record AiRequestRecoveryResult(
        int attempts,
        int recovered,
        int failed,
        int missed
) {
}
