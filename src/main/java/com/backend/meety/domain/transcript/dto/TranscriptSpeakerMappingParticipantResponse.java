package com.backend.meety.domain.transcript.dto;

import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.team.entity.TeamMember;

public record TranscriptSpeakerMappingParticipantResponse(
        Long teamMemberId,
        String nickname
) {

    public static TranscriptSpeakerMappingParticipantResponse from(MeetingParticipant participant) {
        TeamMember teamMember = participant.getTeamMember();
        return new TranscriptSpeakerMappingParticipantResponse(
                teamMember.getId(),
                teamMember.getDisplayName()
        );
    }
}
