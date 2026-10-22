package com.backend.meety.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "connection-scheduler")
public record ConnectionSchedulerProperties(int poolSize) {

    public ConnectionSchedulerProperties {
        if (poolSize < 1) {
            throw new IllegalArgumentException("connection-scheduler.pool-size must be greater than 0");
        }
    }
}
