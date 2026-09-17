package com.backend.meety.domain.auth.dto;

public record TokenRefreshResult(
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn,
        long refreshTokenExpiresIn
) {
}
