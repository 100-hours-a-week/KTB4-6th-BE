package com.backend.meety.domain.auth.dto;

public record LoginResponse(
        Long userId,
        String accessToken,
        String refreshToken,
        long accessTokenExpiresIn,
        long refreshTokenExpiresIn
) {
}
