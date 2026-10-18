package com.backend.meety.domain.ai.repository;

import com.backend.meety.domain.ai.entity.AiRequestType;

public record AiRequestRecoveryCandidate(
        Long id,
        AiRequestType requestType
) {
}
