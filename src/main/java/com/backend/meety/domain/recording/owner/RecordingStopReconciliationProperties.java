package com.backend.meety.domain.recording.owner;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "recording.stop-reconciliation")
public record RecordingStopReconciliationProperties(boolean enabled, Duration interval) {

    public RecordingStopReconciliationProperties {
        if (interval == null || interval.isZero() || interval.isNegative()) {
            throw new IllegalArgumentException("recording.stop-reconciliation.interval must be positive");
        }
    }
}
