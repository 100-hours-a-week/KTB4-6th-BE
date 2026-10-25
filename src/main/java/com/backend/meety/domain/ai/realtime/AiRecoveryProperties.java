package com.backend.meety.domain.ai.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai.recovery")
public record AiRecoveryProperties(int maxAttempts, Duration attemptTimeout, int successAckChunks,
                                   int workers, int queueCapacity) {
    public AiRecoveryProperties {
        if (maxAttempts <= 0 || attemptTimeout == null || attemptTimeout.isZero() || attemptTimeout.isNegative()
                || successAckChunks <= 0 || workers <= 0 || queueCapacity <= 0) {
            throw new IllegalArgumentException("AI recovery settings must be positive");
        }
    }
}
