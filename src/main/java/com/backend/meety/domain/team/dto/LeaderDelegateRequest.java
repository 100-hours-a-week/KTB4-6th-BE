package com.backend.meety.domain.team.dto;

import jakarta.validation.constraints.NotNull;

public record LeaderDelegateRequest(
        @NotNull(message = "위임할 팀원을 선택해주세요.")
        Long teamMemberId
) {
}
