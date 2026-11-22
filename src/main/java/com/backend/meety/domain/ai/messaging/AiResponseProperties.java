package com.backend.meety.domain.ai.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.response")
public record AiResponseProperties(
        int saveMaxAttempts,
        Duration saveRetryInterval
) {

    public AiResponseProperties {
        if (saveMaxAttempts < 1) {
            throw new IllegalArgumentException("ai.response.save-max-attempts must be greater than 0");
        }
        if (saveRetryInterval == null || saveRetryInterval.isNegative()) {
            throw new IllegalArgumentException("ai.response.save-retry-interval must not be negative");
        }
    }
}
