package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.auth.exception.AuthErrorCode;
import com.backend.meety.global.config.JwtProperties;
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

class JwtAuthenticationFilterTest {

    private static final JwtProperties PROPERTIES = new JwtProperties(
            "test-jwt-secret-key-for-meety-32bytes!!", Duration.ofMinutes(30), Duration.ofDays(14));
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-15T00:00:00Z"), ZoneOffset.UTC);

    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(PROPERTIES, CLOCK);
    private final AccessTokenBlacklist blacklist = mock(AccessTokenBlacklist.class);
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(jwtTokenProvider, blacklist);

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("서명이 맞지 않는 AT는 블랙리스트를 조회하지 않고 거절한다")
    void invalidTokenSkipsBlacklist() throws Exception {
        MockHttpServletRequest request = requestWith("not-a-jwt");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        verify(blacklist, never()).contains(anyString());
        assertThat(request.getAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE))
                .isEqualTo(AuthErrorCode.INVALID_ACCESS_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    @Test
    @DisplayName("유효하지만 블랙리스트에 있는 AT는 인증하지 않는다")
    void blacklistedTokenIsRejected() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L);
        when(blacklist.contains(token)).thenReturn(true);
        MockHttpServletRequest request = requestWith(token);

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE))
                .isEqualTo(AuthErrorCode.INVALID_ACCESS_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequest requestWith(String token) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(new Cookie(TokenConstants.ACCESS_TOKEN_COOKIE, token));
        return request;
    }
}
