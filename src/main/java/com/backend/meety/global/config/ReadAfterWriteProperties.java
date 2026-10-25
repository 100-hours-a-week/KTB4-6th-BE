package com.backend.meety.global.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "read-after-write")
public record ReadAfterWriteProperties(Duration window) {

    public ReadAfterWriteProperties {
        if (window == null || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("read-after-write.window must be positive");
        }
    }
}
