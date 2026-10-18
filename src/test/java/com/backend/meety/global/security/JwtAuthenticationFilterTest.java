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
import jakarta.servlet.http.Cookie;
import java.time.Clock;
import java.time.Duration;
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
    @DisplayName("유효하지만 블랙리스트에 있는 AT는 인증하지 않는다")
    void blacklistedTokenIsRejected() throws Exception {
        String token = jwtTokenProvider.createAccessToken(1L);
        when(blacklist.contains(token)).thenReturn(true);

        MockHttpServletRequest request = doFilter(new Cookie(COOKIE_NAME, token));

        assertThat(request.getAttribute(TokenConstants.AUTH_FAILURE_ATTRIBUTE))
                .isEqualTo(AuthErrorCode.INVALID_ACCESS_TOKEN);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private MockHttpServletRequest doFilter(Cookie cookie) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookie);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
        return request;
    }
}
