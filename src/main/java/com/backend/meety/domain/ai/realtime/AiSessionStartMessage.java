package com.backend.meety.domain.ai.realtime;

public record AiSessionStartMessage(
        String type,
        String requestId,
        String meetingId,
        String recordingSessionId,
        AiSessionStartPayload payload
) {

    private static final String TYPE = "session.start";

    public static AiSessionStartMessage of(AiLiveMeetingConnection connection) {
        return new AiSessionStartMessage(
                TYPE,
                connection.sessionStartRequestId(),
                String.valueOf(connection.meetingId()),
                String.valueOf(connection.recordingSessionId()),
                new AiSessionStartPayload(connection.audioFormat().value())
        );
    }
}
