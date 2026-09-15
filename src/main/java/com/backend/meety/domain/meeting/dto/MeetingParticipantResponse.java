package com.backend.meety.domain.meeting.dto;

import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import java.time.LocalDateTime;

public record MeetingParticipantResponse(
        Long participantId,
        Long teamMemberId,
        String displayName,
        ParticipationStatus participationStatus,
        LocalDateTime createdAt
) {

    public static MeetingParticipantResponse from(MeetingParticipant participant) {
        return new MeetingParticipantResponse(
                participant.getId(),
                participant.getTeamMember().getId(),
                participant.getTeamMember().getDisplayName(),
                participant.getParticipationStatus(),
                participant.getCreatedAt()
        );
    }
}
