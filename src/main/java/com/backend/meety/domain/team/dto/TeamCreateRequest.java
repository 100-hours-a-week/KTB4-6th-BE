package com.backend.meety.domain.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TeamCreateRequest(
        @NotBlank(message = "팀 이름을 확인해주세요.")
        @Size(max = 20, message = "팀 이름을 확인해주세요.")
        String name,

        @NotBlank(message = "사용자 이름을 확인해주세요.")
        @Size(max = 10, message = "사용자 이름을 확인해주세요.")
        String displayName
) {
}
