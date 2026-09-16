package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.global.config.JwtProperties;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AccessTokenBlacklistTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "test-jwt-secret-key-for-meety-32bytes!!", Duration.ofMinutes(30), Duration.ofDays(14));

    private final JwtTokenProvider jwtTokenProvider =
            new JwtTokenProvider(PROPERTIES, Clock.systemDefaultZone());
    private final AccessTokenBlacklist blacklist =
            new AccessTokenBlacklist(jwtTokenProvider, Clock.systemDefaultZone());

    @Test
    @DisplayName("등록된 AT는 차단된다")
    void registerAndContains() {
        String token = jwtTokenProvider.createAccessToken(1L);

        blacklist.register(token);

        assertThat(blacklist.contains(token)).isTrue();
    }

    @Test
    @DisplayName("등록하지 않은 AT는 차단되지 않는다")
    void notRegisteredTokenPasses() {
        String token = jwtTokenProvider.createAccessToken(1L);

        assertThat(blacklist.contains(token)).isFalse();
    }

    @Test
    @DisplayName("이미 만료된 AT는 등록하지 않는다")
    void skipExpiredToken() {
        Clock past = Clock.fixed(Instant.now().minus(Duration.ofMinutes(40)), ZoneOffset.UTC);
        String expiredToken = new JwtTokenProvider(PROPERTIES, past).createAccessToken(1L);

        blacklist.register(expiredToken);

        assertThat(blacklist.contains(expiredToken)).isFalse();
    }

    @Test
    @DisplayName("위조 토큰과 null은 등록을 무시한다")
    void skipInvalidToken() {
        blacklist.register("not-a-jwt");
        blacklist.register(null);
        blacklist.register(" ");

        assertThat(blacklist.contains("not-a-jwt")).isFalse();
    }

    @Test
    @DisplayName("차단된 AT도 만료 시각이 지나면 목록에서 제거된다")
    void expiredEntryIsPurged() {
        String token = jwtTokenProvider.createAccessToken(1L);
        Clock afterExpiry = Clock.fixed(Instant.now().plus(Duration.ofMinutes(31)), ZoneOffset.UTC);
        AccessTokenBlacklist futureBlacklist = new AccessTokenBlacklist(jwtTokenProvider, afterExpiry);

        futureBlacklist.register(token);

        assertThat(futureBlacklist.contains(token)).isFalse();
    }
}
