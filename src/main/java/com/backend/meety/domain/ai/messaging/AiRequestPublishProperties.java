package com.backend.meety.domain.ai.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.request.publish")
public record AiRequestPublishProperties(
        int batchSize,
        Duration confirmTimeout
) {

    public AiRequestPublishProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("ai.request.publish.batch-size must be greater than 0");
        }
        if (confirmTimeout == null || confirmTimeout.isZero() || confirmTimeout.isNegative()) {
            throw new IllegalArgumentException("ai.request.publish.confirm-timeout must be positive");
        }
    }
}
