package com.backend.meety.global.security;

import static org.assertj.core.api.Assertions.assertThat;

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

    private final JwtTokenProvider jwtTokenProvider = new JwtTokenProvider(PROPERTIES, Clock.systemDefaultZone());
    private final JwtAuthenticationFilter filter = new JwtAuthenticationFilter(
            jwtTokenProvider,
            new AccessTokenBlacklist(jwtTokenProvider, Clock.systemDefaultZone()),
            new AuthCookieProperties("dev_accessToken"));

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("설정한 이름의 쿠키에 담긴 AT로 인증한다")
    void authenticatesConfiguredCookie() throws Exception {
        doFilter(new Cookie("dev_accessToken", jwtTokenProvider.createAccessToken(1L)));

        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo(1L);
    }

    @Test
    @DisplayName("설정한 이름과 다른 쿠키의 AT는 읽지 않는다")
    void ignoresOtherCookieName() throws Exception {
        doFilter(new Cookie("accessToken", jwtTokenProvider.createAccessToken(1L)));

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }

    private void doFilter(Cookie cookie) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setCookies(cookie);
        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());
    }
}
