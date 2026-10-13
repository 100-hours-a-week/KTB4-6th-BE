package com.backend.meety.domain.recording.owner;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recording.owner")
public record RecordingOwnerProperties(
        Duration ttl
) {
}
