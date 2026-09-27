package com.backend.meety.domain.ai.realtime;

public record AiSessionResumeMessage(
        String type,
        String requestId
) {

    private static final String TYPE = "session.resume";

    public static AiSessionResumeMessage of(AiLiveMeetingConnection connection) {
        return new AiSessionResumeMessage(TYPE, connection.sessionResumeRequestId());
    }
}
