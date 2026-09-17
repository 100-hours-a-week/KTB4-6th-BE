package com.backend.meety.domain.team.dto;

import com.backend.meety.domain.team.entity.TeamMember;
import java.util.List;

public record TeamMemberListResponse(List<TeamMemberItemResponse> members) {

    public static TeamMemberListResponse from(List<TeamMember> teamMembers) {
        return new TeamMemberListResponse(teamMembers.stream()
                .map(TeamMemberItemResponse::from)
                .toList());
    }
}
