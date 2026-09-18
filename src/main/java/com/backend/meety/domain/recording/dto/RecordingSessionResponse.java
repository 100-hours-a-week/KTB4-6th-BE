package com.backend.meety.domain.recording.dto;

import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import java.time.LocalDateTime;

public record RecordingSessionResponse(
        Long recordingSessionId,
        Long meetingId,
        Long startedByTeamMemberId,
        RecordingSessionStatus status,
        LocalDateTime startedAt,
        LocalDateTime pausedAt,
        LocalDateTime endedAt,
        LocalDateTime autoEndAt
) {

    public static RecordingSessionResponse from(RecordingSession session) {
        return new RecordingSessionResponse(
                session.getId(), session.getMeeting().getId(), session.getStartedByTeamMember().getId(),
                session.getStatus(), session.getStartedAt(), session.getPausedAt(), session.getEndedAt(),
                session.getAutoEndAt()
        );
    }
}
