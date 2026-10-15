package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.team.entity.TeamMember;

public record AiSessionParticipant(
        Long teamMemberId,
        String name
) {

    public static AiSessionParticipant from(MeetingParticipant attendee) {
        TeamMember teamMember = attendee.getTeamMember();
        if (teamMember == null) {
            return new AiSessionParticipant(null, attendee.getDisplayName());
        }
        return new AiSessionParticipant(teamMember.getId(), teamMember.getDisplayName());
    }
}
