package com.backend.meety.domain.meeting.dto;

import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.team.entity.TeamMember;

public record MeetingAttendeeResponse(
        Long teamMemberId,
        String name,
        MeetingAttendeeType type
) {

    public static MeetingAttendeeResponse from(MeetingParticipant attendee) {
        TeamMember teamMember = attendee.getTeamMember();
        if (teamMember == null) {
            return new MeetingAttendeeResponse(null, attendee.getDisplayName(), MeetingAttendeeType.CUSTOM);
        }
        return new MeetingAttendeeResponse(
                teamMember.getId(), teamMember.getDisplayName(), MeetingAttendeeType.TEAM_MEMBER);
    }
}
