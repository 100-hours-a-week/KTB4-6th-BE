package com.backend.meety.domain.recording.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recording.sequence")
public record AudioSequenceProperties(
        Duration ttl
) {
}
