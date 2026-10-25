package com.backend.meety.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transcript-save.executor")
public record TranscriptSaveExecutorProperties(
        int corePoolSize,
        int maxPoolSize,
        int queueCapacity,
        int shutdownAwaitSeconds
) {

    public TranscriptSaveExecutorProperties {
        if (corePoolSize < 1) {
            throw new IllegalArgumentException("transcript-save.executor.core-pool-size must be greater than 0");
        }
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("transcript-save.executor.max-pool-size must be at least core-pool-size");
        }
        if (queueCapacity < 0 || shutdownAwaitSeconds < 0) {
            throw new IllegalArgumentException(
                    "transcript-save.executor queue capacity and shutdown wait must not be negative");
        }
    }
}
