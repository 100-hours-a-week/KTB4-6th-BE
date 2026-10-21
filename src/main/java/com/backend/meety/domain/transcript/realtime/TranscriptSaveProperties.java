package com.backend.meety.domain.transcript.realtime;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "transcript-save")
public record TranscriptSaveProperties(
        int maxPendingPerRecording,
        int maxPendingTotal,
        Duration retryInitialBackoff,
        Duration retryMaxBackoff,
        Duration retryMaxDuration
) {

    public TranscriptSaveProperties {
        if (maxPendingPerRecording < 1 || maxPendingTotal < maxPendingPerRecording) {
            throw new IllegalArgumentException(
                    "transcript-save limits must be positive and max-pending-total must be at least max-pending-per-recording");
        }
        if (retryInitialBackoff == null || retryInitialBackoff.isNegative() || retryInitialBackoff.isZero()) {
            throw new IllegalArgumentException("transcript-save.retry-initial-backoff must be positive");
        }
        if (retryMaxBackoff == null || retryMaxBackoff.compareTo(retryInitialBackoff) < 0) {
            throw new IllegalArgumentException("transcript-save.retry-max-backoff must be at least retry-initial-backoff");
        }
        if (retryMaxDuration == null || retryMaxDuration.isNegative()) {
            throw new IllegalArgumentException("transcript-save.retry-max-duration must not be negative");
        }
    }
}
