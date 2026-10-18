package com.backend.meety.domain.ai;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.request.recovery")
public record AiRequestRecoveryProperties(
        Duration processingTimeout,
        int batchSize
) {

    public AiRequestRecoveryProperties {
        if (processingTimeout == null || processingTimeout.isZero() || processingTimeout.isNegative()) {
            throw new IllegalArgumentException("ai.request.recovery.processing-timeout must be positive");
        }
        if (batchSize < 1) {
            throw new IllegalArgumentException("ai.request.recovery.batch-size must be greater than 0");
        }
    }
}
