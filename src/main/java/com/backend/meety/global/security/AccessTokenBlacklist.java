package com.backend.meety.global.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AccessTokenBlacklist {

    private final Map<String, Instant> blacklist = new ConcurrentHashMap<>();
    private final JwtTokenProvider jwtTokenProvider;
    private final Clock clock;

    public void register(String accessToken) {
        try {
            Claims claims = jwtTokenProvider.validateAndDecode(accessToken);
            blacklist.put(accessToken, claims.getExpiration().toInstant());
        } catch (JwtException | IllegalArgumentException e) {
            return;
        }
        purgeExpired();
    }

    public boolean contains(String accessToken) {
        Instant expiresAt = blacklist.get(accessToken);
        if (expiresAt == null) {
            return false;
        }
        if (expiresAt.isBefore(clock.instant())) {
            blacklist.remove(accessToken);
            return false;
        }
        return true;
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        blacklist.values().removeIf(expiresAt -> expiresAt.isBefore(now));
    }
}
