package com.backend.meety.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AccessTokenBlacklist {

    private static final String KEY_PREFIX = "at:blacklist:";
    private static final String VALUE = "1";

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;

    public void register(String accessToken) {
        Claims claims;
        try {
            claims = jwtTokenProvider.validateAndDecode(accessToken);
        } catch (JwtException | IllegalArgumentException e) {
            return;
        }
        Duration ttl = Duration.between(clock.instant(), claims.getExpiration().toInstant());
        if (ttl.isNegative() || ttl.isZero()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(key(accessToken), VALUE, ttl);
        } catch (DataAccessException e) {
            log.warn("AT 블랙리스트 등록에 실패했습니다.", e);
        }
    }

    public boolean contains(String accessToken) {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(key(accessToken)));
        } catch (DataAccessException e) {
            log.warn("AT 블랙리스트 조회에 실패해 토큰을 차단합니다.", e);
            return true;
        }
    }

    private String key(String accessToken) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(accessToken.getBytes(StandardCharsets.UTF_8));
            return KEY_PREFIX + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
