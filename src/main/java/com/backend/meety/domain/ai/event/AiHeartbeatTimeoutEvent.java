package com.backend.meety.domain.ai.event;

import java.time.Instant;

/** Detection only. The consumer must revalidate the connection generation before recovery. */
public record AiHeartbeatTimeoutEvent(
        Long recordingSessionId, String sessionStartRequestId,
        String pingRequestId, int consecutiveFailures, Instant detectedAt
) {
    public String reason() {
        return "AI_HEARTBEAT_TIMEOUT";
    }
}
