package com.backend.meety.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LogoutRequest(
        @NotBlank(message = "액세스 토큰은 필수입니다.")
        String accessToken,
        @NotBlank(message = "리프레시 토큰은 필수입니다.")
        String refreshToken
) {
}
