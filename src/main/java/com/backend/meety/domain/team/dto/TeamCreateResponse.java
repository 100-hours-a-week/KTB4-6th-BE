package com.backend.meety.domain.team.dto;

import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamInvitationCode;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.entity.TeamMemberRole;
import java.time.LocalDateTime;

public record TeamCreateResponse(
        Long teamId,
        String name,
        Long teamMemberId,
        String displayName,
        TeamMemberRole role,
        MembershipStatus membershipStatus,
        String invitationCode,
        LocalDateTime createdAt
) {

    public static TeamCreateResponse of(Team team, TeamMember teamMember, TeamInvitationCode invitationCode) {
        return new TeamCreateResponse(
                team.getId(),
                team.getName(),
                teamMember.getId(),
                teamMember.getDisplayName(),
                teamMember.getRole(),
                teamMember.getMembershipStatus(),
                invitationCode.getCode(),
                team.getCreatedAt()
        );
    }
}
