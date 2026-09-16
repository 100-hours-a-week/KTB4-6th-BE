package com.backend.meety.domain.auth.service;

import com.backend.meety.domain.auth.dto.RefreshTokenRotation;
import com.backend.meety.domain.auth.entity.RefreshToken;
import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.domain.auth.repository.RefreshTokenRepository;
import com.backend.meety.global.config.JwtProperties;
import com.backend.meety.global.security.TokenConstants;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final JwtProperties jwtProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Transactional
    public String issue(Long userId) {
        return createToken(userId);
    }

    @Transactional
    public RefreshTokenRotation rotate(String rawToken) {
        String tokenHash = hash(rawToken);
        RefreshToken refreshToken = refreshTokenRepository.findByTokenHash(tokenHash)
                .orElseThrow(() -> new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN));
        LocalDateTime now = LocalDateTime.now(clock);
        validateNotExpired(refreshToken, now);
        markUsed(tokenHash, now);
        Long userId = refreshToken.getUserId();
        return new RefreshTokenRotation(userId, createToken(userId));
    }

    private String createToken(Long userId) {
        String rawToken = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(jwtProperties.refreshTokenValidity());
        refreshTokenRepository.save(RefreshToken.of(userId, hash(rawToken), expiresAt));
        return rawToken;
    }

    @Transactional
    public void revoke(String rawToken) {
        refreshTokenRepository.markUsedIfActive(hash(rawToken), LocalDateTime.now(clock));
    }

    @Transactional
    public void revokeAll(Long userId) {
        refreshTokenRepository.revokeAllByUserId(userId, LocalDateTime.now(clock));
    }

    private void validateNotExpired(RefreshToken refreshToken, LocalDateTime now) {
        if (refreshToken.isExpired(now)) {
            throw new AuthException(AuthErrorCode.EXPIRED_REFRESH_TOKEN);
        }
    }

    private void markUsed(String tokenHash, LocalDateTime now) {
        if (refreshTokenRepository.markUsedIfActive(tokenHash, now) == 0) {
            throw new AuthException(AuthErrorCode.INVALID_REFRESH_TOKEN);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[TokenConstants.REFRESH_TOKEN_BYTE_LENGTH];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hash(String rawToken) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
