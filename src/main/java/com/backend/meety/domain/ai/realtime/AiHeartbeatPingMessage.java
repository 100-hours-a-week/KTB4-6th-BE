package com.backend.meety.domain.ai.realtime;

public record AiHeartbeatPingMessage(String type, String requestId, String recordingSessionId) {
    public static AiHeartbeatPingMessage of(Long recordingSessionId, String requestId) {
        return new AiHeartbeatPingMessage("heartbeat.ping", requestId, String.valueOf(recordingSessionId));
    }
}
