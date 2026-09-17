package com.backend.meety.domain.team.dto;

import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.entity.TeamMemberRole;
import java.time.LocalDateTime;

public record TeamDetailResponse(
        Long teamId,
        String name,
        Long teamMemberId,
        String displayName,
        TeamMemberRole role,
        String invitationCode,
        LocalDateTime createdAt
) {

    public static TeamDetailResponse of(Team team, TeamMember teamMember, String invitationCode) {
        return new TeamDetailResponse(
                team.getId(),
                team.getName(),
                teamMember.getId(),
                teamMember.getDisplayName(),
                teamMember.getRole(),
                invitationCode,
                team.getCreatedAt()
        );
    }
}
