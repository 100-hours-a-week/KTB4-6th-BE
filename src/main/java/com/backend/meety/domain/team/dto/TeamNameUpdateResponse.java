package com.backend.meety.domain.team.dto;

import com.backend.meety.domain.team.entity.Team;
import java.time.LocalDateTime;

public record TeamNameUpdateResponse(
        Long teamId,
        String name,
        LocalDateTime updatedAt
) {

    public static TeamNameUpdateResponse from(Team team) {
        return new TeamNameUpdateResponse(team.getId(), team.getName(), team.getUpdatedAt());
    }
}
