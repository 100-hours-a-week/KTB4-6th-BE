package com.backend.meety.domain.ai.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai.heartbeat")
public record AiHeartbeatProperties(Duration interval, Duration pongTimeout, int failureThreshold) {
    public AiHeartbeatProperties {
        if (interval == null || interval.isZero() || interval.isNegative()
                || pongTimeout == null || pongTimeout.isZero() || pongTimeout.isNegative()
                || failureThreshold <= 0) {
            throw new IllegalArgumentException("Heartbeat interval, pong timeout and failure threshold must be positive");
        }
    }
}
