package com.backend.meety.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "chat.executor")
public record ChatExecutorProperties(
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity
) {

    public ChatExecutorProperties {
        if (corePoolSize < 1) {
            throw new IllegalArgumentException("chat.executor.core-pool-size must be greater than 0");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException(
                    "chat.executor.max-pool-size must be greater than or equal to core-pool-size");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("chat.executor.queue-capacity must not be negative");
        }
    }
}
