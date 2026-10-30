package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.global.config.AuthCookieProperties;
import com.backend.meety.global.config.JwtProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

class JwtAuthenticationFilterTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "test-jwt-secret-key-for-meety-32bytes!!", Duration.ofMinutes(30), Duration.ofDays(14));
    private static final String COOKIE_NAME = "dev_accessToken";

    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(PROPERTIES, Clock.systemDefaultZone());
    private final AccessTokenBlacklist blacklist = mock(AccessTokenBlacklist.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
            jwtTokenProvider, blacklist, new AuthCookieProperties(COOKIE_NAME));

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("설정한 이름의 쿠키에 담긴 AT로 인증한다")
    void authenticatesConfiguredCookie() throws Exception {
        doFilter(new Cookie(COOKIE_NAME, jwtTokenProvider.createAccessToken(1L)));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(1L);
    }

    @Test
    @DisplayName("설정한 이름과 다른 쿠키의 AT는 읽지 않는다")
    void ignoresOtherCookieName() throws Exception {
        doFilter(new Cookie("accessToken", jwtTokenProvider.createAccessToken(1L)));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("서명이 맞지 않는 AT는 블랙리스트를 조회하지 않고 거절한다")
    void invalidTokenSkipsBlacklist() throws Exception {
        MockHttpServletRequest request = doFilter(new Cookie(COOKIE_NAME, "not-a-jwt"));

        verify(blacklist, never()).contains(anyString());
        assertThat(request.getAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE))
                .isEqualTo(AuthErrorCode.INVALID_ACCESS_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("다른 서명 키로 발급된 JWT는 Redis 장애여도 거부한다")
    void rejectsForgedTokenBeforeRedisLookup() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));
        AccessTokenBlacklist failOpenBlacklist = new AccessTokenBlacklist(
                redisTemplate, jwtTokenProvider, Clock.systemDefaultZone(), new SimpleMeterRegistry());
        JwtAuthenticationFilter failOpenFilter = new JwtAuthenticationFilter(
                jwtTokenProvider, failOpenBlacklist, new AuthCookieProperties(COOKIE_NAME));
        String otherSecret = "another-test-secret-key-for-meety-32bytes!!";
        JwtTokenProvider forgedTokenProvider = new JwtTokenProvider(
                new JwtProperties(otherSecret, Duration.ofMinutes(30), Duration.ofDays(14)),
                Clock.systemDefaultZone());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, forgedTokenProvider.createAccessToken(1L)));
        failOpenFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(redisTemplate, never()).hasKey(org.mockito.ArgumentMatchers.anyString());
        assertThat(request.getAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE))
                .isEqualTo(AuthErrorCode.INVALID_ACCESS_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("유효하지만 블랙리스트에 있는 AT는 인증하지 않는다")
    void blacklistedTokenIsRejected() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L);
        when(blacklist.contains(token)).thenReturn(true);

        MockHttpServletRequest request = doFilter(new Cookie(COOKIE_NAME, token));

        assertThat(request.getAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE))
                .isEqualTo(AuthErrorCode.INVALID_ACCESS_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("Redis 연결 장애여도 유효한 JWT는 인증한다")
    void authenticatesValidTokenWhenRedisUnavailable() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));
        AccessTokenBlacklist failOpenBlacklist = new AccessTokenBlacklist(
                redisTemplate, jwtTokenProvider, Clock.systemDefaultZone(), new SimpleMeterRegistry());
        JwtAuthenticationFilter failOpenFilter = new JwtAuthenticationFilter(
                jwtTokenProvider, failOpenBlacklist, new AuthCookieProperties(COOKIE_NAME));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, jwtTokenProvider.createAccessToken(2L)));

        failOpenFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(2L);
    }

    @Test
    @DisplayName("만료된 JWT는 Redis 장애여도 거부하고 블랙리스트를 조회하지 않는다")
    void rejectsExpiredTokenBeforeRedisLookup() throws Exception {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        when(redisTemplate.hasKey(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RedisConnectionFailureException("down"));
        AccessTokenBlacklist failOpenBlacklist = new AccessTokenBlacklist(
                redisTemplate, jwtTokenProvider, Clock.systemDefaultZone(), new SimpleMeterRegistry());
        JwtAuthenticationFilter failOpenFilter = new JwtAuthenticationFilter(
                jwtTokenProvider, failOpenBlacklist, new AuthCookieProperties(COOKIE_NAME));
        JwtTokenProvider expiredTokenProvider = new JwtTokenProvider(PROPERTIES,
                Clock.fixed(Clock.systemUTC().instant().minus(1, ChronoUnit.HOURS), Clock.systemUTC().getZone()));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(COOKIE_NAME, expiredTokenProvider.createAccessToken(2L)));

        failOpenFilter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE))
                .isEqualTo(AuthErrorCode.EXPIRED_ACCESS_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(redisTemplate, never()).hasKey(org.mockito.ArgumentMatchers.anyString());
    }

    private MockHttpServletRequest doFilter(Cookie cookie) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookie);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return request;
    }
}
