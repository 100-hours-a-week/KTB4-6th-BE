package com.backend.meety.domain.auth.service;

import com.backend.meety.domain.auth.entity.RefreshToken;
import com.backend.meety.domain.auth.repository.RefreshTokenRepository;
import com.backend.meety.domain.user.entity.User;
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
    public String issue(User user) {
        String rawToken = generateToken();
        LocalDateTime expiresAt = LocalDateTime.now(clock).plus(jwtProperties.refreshTokenValidity());
        refreshTokenRepository.save(RefreshToken.of(user, hash(rawToken), expiresAt));
        return rawToken;
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
