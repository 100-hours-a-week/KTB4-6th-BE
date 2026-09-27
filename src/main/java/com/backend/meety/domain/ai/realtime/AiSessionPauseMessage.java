package com.backend.meety.domain.ai.realtime;

public record AiSessionPauseMessage(
        String type,
        String requestId
) {

    private static final String TYPE = "session.pause";

    public static AiSessionPauseMessage of(AiLiveMeetingConnection connection) {
        return new AiSessionPauseMessage(TYPE, connection.sessionPauseRequestId());
    }
}
