package com.backend.meety.domain.team.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MyTeamResponse(
        boolean hasActiveTeam,
        Long teamId
) {

    public static MyTeamResponse noTeam() {
        return new MyTeamResponse(false, null);
    }

    public static MyTeamResponse of(Long teamId) {
        return new MyTeamResponse(true, teamId);
    }
}
