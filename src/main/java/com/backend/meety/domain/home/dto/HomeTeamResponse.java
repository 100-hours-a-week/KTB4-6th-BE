package com.backend.meety.domain.home.dto;

public record HomeTeamResponse(
        Long teamId,
        String name,
        String invitationCode,
        long memberCount
) {
}
