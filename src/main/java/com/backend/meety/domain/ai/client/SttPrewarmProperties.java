package com.backend.meety.domain.ai.client;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ai.stt.prewarm")
public record SttPrewarmProperties(Duration schedulerDelay, Duration lookahead, Duration readTimeout) {

    public SttPrewarmProperties {
        if (schedulerDelay == null || schedulerDelay.isZero() || schedulerDelay.isNegative()
                || lookahead == null || lookahead.isZero() || lookahead.isNegative()
                || readTimeout == null || readTimeout.isZero() || readTimeout.isNegative()) {
            throw new IllegalArgumentException("STT prewarm delay, lookahead and read timeout must be positive");
        }
    }
}
