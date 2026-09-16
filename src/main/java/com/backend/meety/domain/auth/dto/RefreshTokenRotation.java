package com.backend.meety.domain.auth.dto;

public record RefreshTokenRotation(Long userId, String refreshToken) {
}
