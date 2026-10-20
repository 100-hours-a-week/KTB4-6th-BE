package com.backend.meety.domain.ai.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("ai.audio-ack")
public record AiAudioAckProperties(Duration timeout, Duration pollDelay, int maxPendingChunks) {
    public AiAudioAckProperties {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || pollDelay == null || pollDelay.isNegative() || pollDelay.isZero() || maxPendingChunks <= 0) {
            throw new IllegalArgumentException("Audio ACK timeout, poll delay and pending limit must be positive");
        }
    }
}
