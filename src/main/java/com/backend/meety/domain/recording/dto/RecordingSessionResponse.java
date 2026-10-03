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
        Long totalPausedDurationMs,
        LocalDateTime endedAt,
        LocalDateTime autoEndAt,
        Long receivedChunkCount
) {

    public static RecordingSessionResponse from(RecordingSession session) {
        return from(session, null);
    }

    public static RecordingSessionResponse from(RecordingSession session, Long receivedChunkCount) {
        return new RecordingSessionResponse(
                session.getId(), session.getMeeting().getId(), session.getStartedByTeamMember().getId(),
                session.getStatus(), session.getStartedAt(), session.getPausedAt(),
                session.getTotalPausedDurationMs(), session.getEndedAt(), session.getAutoEndAt(),
                receivedChunkCount
        );
    }
}
