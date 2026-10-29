package com.backend.meety.domain.dashboard.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.dashboard.dto.DashboardChartPointResponse;
import com.backend.meety.domain.dashboard.dto.DashboardResponse;
import com.backend.meety.domain.dashboard.dto.DashboardSummaryResponse;
import com.backend.meety.domain.dashboard.service.DashboardService;
import com.backend.meety.global.config.AuthCookieProperties;
import com.backend.meety.global.config.CorsProperties;
import com.backend.meety.global.config.InternalApiProperties;
import com.backend.meety.global.security.AccessTokenBlacklist;
import com.backend.meety.global.security.CustomAuthenticationEntryPoint;
import com.backend.meety.global.security.JwtTokenProvider;
import com.backend.meety.global.security.SecurityConfig;
import com.backend.meety.global.security.TokenConstants;
import io.jsonwebtoken.Claims;
import jakarta.servlet.http.Cookie;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(value = DashboardController.class, properties = {
        "spring.security.oauth2.client.registration.kakao.client-id=test-client",
        "spring.security.oauth2.client.registration.kakao.client-secret=test-secret",
        "spring.security.oauth2.client.registration.kakao.redirect-uri=http://localhost/callback"
})
@Import({SecurityConfig.class, CustomAuthenticationEntryPoint.class})
class DashboardControllerSecurityTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DashboardService service;
    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;
    @MockitoBean
    private AccessTokenBlacklist accessTokenBlacklist;
    @MockitoBean
    private CorsProperties corsProperties;
    @MockitoBean
    private AuthCookieProperties authCookieProperties;
    @MockitoBean
    private InternalApiProperties internalApiProperties;

    @BeforeEach
    void setUp() {
        when(authCookieProperties.accessTokenCookieName()).thenReturn("accessToken");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Bearer test-access-token"})
    @DisplayName("인증 쿠키가 없는 요청은 Bearer 헤더 유무와 관계없이 401을 반환한다")
    void requestsWithoutCookieRemainUnauthorized(String authorization) throws Exception {
        // 테스트 목적:
        // 대시보드의 새 날짜 계약이 기존 쿠키 인증을 바꾸지 않으며
        // 쿠키가 없으면 Bearer 헤더만으로 인증되지 않는지 검증한다.

        // given

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard")
                        .param("from", "2026-10-12").param("to", "2026-10-12")
                        .header("Authorization", authorization))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(service, jwtTokenProvider);
    }

    @Test
    @DisplayName("기존 accessToken 쿠키로 인증하면 날짜 기반 대시보드를 조회할 수 있다")
    void configuredCookieAuthenticatesDashboardRequest() throws Exception {
        // 테스트 목적:
        // 기존 SecurityFilterChain과 쿠키 인증을 거친 사용자의 식별자가
        // 날짜 기반 대시보드 서비스로 그대로 전달되는지 검증한다.

        // given
        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(claims.get(TokenConstants.ROLE_CLAIM, String.class)).thenReturn("USER");
        when(jwtTokenProvider.validateAndDecode("test-access-token")).thenReturn(claims);
        LocalDate date = LocalDate.of(2026, 10, 12);
        when(service.getDashboard(1L, 2L, date, date)).thenReturn(new DashboardResponse(
                1, date, date,
                new DashboardSummaryResponse(0L, 0L, 0L, 0L, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0L),
                List.of(new DashboardChartPointResponse(date, date, 0L, 0L, 0L, 0L)), List.of(), List.of()));

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard")
                        .param("from", date.toString()).param("to", date.toString())
                        .cookie(new Cookie("accessToken", "test-access-token")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.periodDays").value(1));
        verify(service).getDashboard(1L, 2L, date, date);
    }
}
