package com.backend.meety.domain.team.dto;

import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.entity.TeamMemberRole;
import java.time.LocalDateTime;

public record TeamJoinResponse(
        Long teamId,
        String teamName,
        Long teamMemberId,
        String displayName,
        TeamMemberRole role,
        MembershipStatus membershipStatus,
        LocalDateTime createdAt
) {

    public static TeamJoinResponse of(Team team, TeamMember teamMember) {
        return new TeamJoinResponse(
                team.getId(),
                team.getName(),
                teamMember.getId(),
                teamMember.getDisplayName(),
                teamMember.getRole(),
                teamMember.getMembershipStatus(),
                teamMember.getCreatedAt()
        );
    }
}
