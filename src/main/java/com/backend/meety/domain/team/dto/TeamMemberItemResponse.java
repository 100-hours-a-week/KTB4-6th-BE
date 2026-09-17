package com.backend.meety.domain.team.dto;

import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.entity.TeamMemberRole;

public record TeamMemberItemResponse(
        Long teamMemberId,
        String displayName,
        TeamMemberRole role
) {

    public static TeamMemberItemResponse from(TeamMember teamMember) {
        return new TeamMemberItemResponse(
                teamMember.getId(),
                teamMember.getDisplayName(),
                teamMember.getRole()
        );
    }
}
