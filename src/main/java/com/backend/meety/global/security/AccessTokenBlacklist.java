package com.backend.meety.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.HexFormat;
import org.slf4j.MDC;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AccessTokenBlacklist {

    private static final String KEY_PREFIX = "at:blacklist:";
    private static final String VALUE = "1";

    private final StringRedisTemplate redisTemplate;
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;
    private final Counter redisUnavailable;

    public AccessTokenBlacklist(StringRedisTemplate redisTemplate, JwtTokenProvider jwtTokenProvider,
                                Clock clock, MeterRegistry meterRegistry) {
        this.redisTemplate = redisTemplate;
        this.jwtTokenProvider = jwtTokenProvider;
        this.clock = clock;
        this.redisUnavailable = meterRegistry.counter("security.at.blacklist.redis.fail_open");
    }

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
        } catch (DataAccessResourceFailureException | QueryTimeoutException e) {
            redisUnavailable.increment();
            Throwable rootCause = rootCause(e);
            String requestId = MDC.get("requestId");
            if (requestId == null) {
                requestId = MDC.get("traceId");
            }
            log.warn("event=AT_BLACKLIST_REDIS_UNAVAILABLE exceptionType={} rootCauseType={} "
                            + "failOpen=true requestId={}",
                    e.getClass().getSimpleName(), rootCause.getClass().getSimpleName(), requestId);
            return false;
        }
    }

    private Throwable rootCause(Throwable throwable) {
        Throwable cause = throwable;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause;
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
