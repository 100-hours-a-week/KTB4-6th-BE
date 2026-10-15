package com.backend.meety.domain.ai.realtime;

import java.util.List;

public record AiSessionStartMessage(
        String type,
        String requestId,
        Long meetingId,
        Long recordingSessionId,
        int participantCount,
        List<AiSessionParticipant> participants
) {

    private static final String TYPE = "session.start";

    public static AiSessionStartMessage of(
            AiLiveMeetingConnection connection, List<AiSessionParticipant> participants) {
        return new AiSessionStartMessage(
                TYPE,
                connection.sessionStartRequestId(),
                connection.meetingId(),
                connection.recordingSessionId(),
                participants.size(),
                participants
        );
    }
}
