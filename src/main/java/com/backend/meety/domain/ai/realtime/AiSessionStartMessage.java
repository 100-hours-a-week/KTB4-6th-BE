package com.backend.meety.domain.ai.realtime;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.backend.meety.domain.meeting.entity.Meeting;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiSessionStartMessage(
        String type,
        String requestId,
        Long meetingId,
        Long recordingSessionId,
        Long teamId,
        String title,
        String purpose,
        String notes,
        int participantCount,
        List<AiSessionParticipant> participants,
        Long startSequence,
        Long transcriptSequenceStart
) {

    private static final String TYPE = "session.start";

    public static AiSessionStartMessage of(
            AiLiveMeetingConnection connection, Meeting meeting, List<AiSessionParticipant> participants) {
        return new AiSessionStartMessage(
                TYPE,
                connection.sessionStartRequestId(),
                connection.meetingId(),
                connection.recordingSessionId(),
                meeting.getTeam().getId(),
                meeting.getTitle(),
                meeting.getPurpose(),
                meeting.getNote(),
                participants.size(),
                participants,
                connection.startSequence(),
                connection.transcriptSequenceStart()
        );
    }
}
