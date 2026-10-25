package com.backend.meety.domain.ai.event;

import java.time.Instant;

/** Detection only. Recovery/fatal policies belong to their respective follow-up issues. */
public record AiAudioAckTimeoutEvent(
        Long recordingSessionId, String sessionStartRequestId,
        long lastSentSequence, long lastAckedSequence, Instant detectedAt
) {
    public String reason() {
        return "AUDIO_ACK_TIMEOUT";
    }
}
