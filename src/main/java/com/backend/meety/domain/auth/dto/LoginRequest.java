package com.backend.meety.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LoginRequest(
        @NotBlank(message = "인가 코드는 필수입니다.")
        String authorizationCode
) {
}
