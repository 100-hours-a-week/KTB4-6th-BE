package com.backend.meety.domain.ai.realtime;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiSessionStartMessage(
        String type,
        String requestId,
        Long meetingId,
        Long recordingSessionId,
        int participantCount,
        List<AiSessionParticipant> participants,
        Long startSequence,
        Long transcriptSequenceStart
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
                participants,
                connection.startSequence(),
                connection.transcriptSequenceStart()
        );
    }
}
