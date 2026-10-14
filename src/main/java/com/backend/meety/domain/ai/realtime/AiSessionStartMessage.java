package com.backend.meety.domain.ai.realtime;

import java.util.List;

public record AiSessionStartMessage(
        String type,
        String requestId,
        String meetingId,
        String recordingSessionId,
        int participantCount,
        List<AiSessionParticipant> participants
) {

    private static final String TYPE = "session.start";

    public static AiSessionStartMessage of(
            AiLiveMeetingConnection connection, List<AiSessionParticipant> participants) {
        return new AiSessionStartMessage(
                TYPE,
                connection.sessionStartRequestId(),
                String.valueOf(connection.meetingId()),
                String.valueOf(connection.recordingSessionId()),
                participants.size(),
                participants
        );
    }
}
