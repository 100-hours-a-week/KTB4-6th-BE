package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.domain.auth.exception.AuthException;
import com.backend.meety.global.config.AuthCookieProperties;
import com.backend.meety.global.config.JwtProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.http.server.ServletServerHttpRequest;

class WebSocketCookieAuthenticationTest {

    private static final String COOKIE_NAME = "dev_accessToken";
    private static final JwtProperties PROPERTIES = new JwtProperties(
            "test-jwt-secret-key-for-meety-32bytes!!", Duration.ofMinutes(30), Duration.ofDays(14));

    private final Clock clock = Clock.systemUTC();
    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(PROPERTIES, clock);
    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final AccessTokenBlacklist blacklist =
            new AccessTokenBlacklist(redisTemplate, jwtTokenProvider, clock, new SimpleMeterRegistry());
    private final WebSocketCookieAuthentication authentication = new WebSocketCookieAuthentication(
            jwtTokenProvider, blacklist, new AuthCookieProperties(COOKIE_NAME));

    @Test
    @DisplayName("Redis 연결 장애여도 유효한 JWT로 WebSocket 인증을 허용한다")
    void allowsValidJwtWhenRedisUnavailable() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        MockHttpServletRequest request = requestWithToken(jwtTokenProvider.createAccessToken(7L));

        Long userId = authentication.authenticate(new ServletServerHttpRequest(request));

        assertThat(userId).isEqualTo(7L);
    }

    @Test
    @DisplayName("실제 블랙리스트 토큰은 Redis가 정상일 때 WebSocket 인증을 거부한다")
    void rejectsBlacklistedJwt() {
        String token = jwtTokenProvider.createAccessToken(7L);
        when(redisTemplate.hasKey(anyString())).thenReturn(true);
        MockHttpServletRequest request = requestWithToken(token);

        assertThatThrownBy(() -> authentication.authenticate(new ServletServerHttpRequest(request)))
                .isInstanceOfSatisfying(AuthException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_ACCESS_TOKEN));
    }

    @Test
    @DisplayName("잘못된 JWT는 Redis 장애와 무관하게 거부하고 블랙리스트를 조회하지 않는다")
    void rejectsInvalidJwtBeforeRedisLookup() {
        when(redisTemplate.hasKey(anyString())).thenThrow(new RedisConnectionFailureException("down"));
        MockHttpServletRequest request = requestWithToken("invalid-token");

        assertThatThrownBy(() -> authentication.authenticate(new ServletServerHttpRequest(request)))
                .isInstanceOfSatisfying(AuthException.class,
                        exception -> assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.INVALID_ACCESS_TOKEN));
        verify(redisTemplate, never()).hasKey(anyString());
    }

    private MockHttpServletRequest requestWithToken(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, token));
        return request;
    }
}
