package com.backend.meety.domain.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TeamNameUpdateRequest(
        @NotBlank(message = "팀 이름을 확인해주세요.")
        @Pattern(regexp = "^[가-힣a-zA-Z0-9]{2,10}$", message = "팀 이름을 확인해주세요.")
        String name
) {
}
