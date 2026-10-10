package com.backend.meety.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "notification.executor")
public record NotificationExecutorProperties(
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity
) {

    public NotificationExecutorProperties {
        if (corePoolSize < 1) {
            throw new IllegalArgumentException("notification.executor.core-pool-size must be greater than 0");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException(
                    "notification.executor.max-pool-size must be greater than or equal to core-pool-size");
        }
        if (queueCapacity < 0) {
            throw new IllegalArgumentException("notification.executor.queue-capacity must not be negative");
        }
    }
}
