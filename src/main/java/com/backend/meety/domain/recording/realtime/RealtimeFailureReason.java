package com.backend.meety.domain.recording.realtime;

public enum RealtimeFailureReason {
    AI_PROCESSING_ERROR,
    AI_CONNECTION_CLOSED,
    AI_TRANSPORT_ERROR,
    AI_HEARTBEAT_TIMEOUT,
    AUDIO_ACK_TIMEOUT,
    AUDIO_FORWARD_FAILED,
    TRANSCRIPT_PROCESSING_FAILED,
    AI_INBOUND_PROCESSING_FAILED;

    public static RealtimeFailureReason fromRecovery(String reason) {
        if ("AI_UNEXPECTED_CLOSE".equals(reason)) { return AI_CONNECTION_CLOSED; }
        if ("AUDIO_FORWARD_FAILURE".equals(reason)) { return AUDIO_FORWARD_FAILED; }
        try { return valueOf(reason); }
        catch (IllegalArgumentException | NullPointerException ignored) { return AI_CONNECTION_CLOSED; }
    }
}
