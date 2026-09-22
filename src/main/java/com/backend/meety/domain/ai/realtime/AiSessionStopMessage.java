package com.backend.meety.domain.ai.realtime;

public record AiSessionStopMessage(
        String type,
        String requestId
) {

    private static final String TYPE = "session.stop";

    public static AiSessionStopMessage of(AiLiveMeetingConnection connection) {
        return new AiSessionStopMessage(TYPE, connection.sessionStopRequestId());
    }
}
