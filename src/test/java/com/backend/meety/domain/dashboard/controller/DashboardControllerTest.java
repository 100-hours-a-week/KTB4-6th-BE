package com.backend.meety.domain.dashboard.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.dashboard.dto.DashboardChartPointResponse;
import com.backend.meety.domain.dashboard.dto.DashboardResponse;
import com.backend.meety.domain.dashboard.dto.DashboardSummaryResponse;
import com.backend.meety.domain.dashboard.dto.DashboardTopicResponse;
import com.backend.meety.domain.dashboard.exception.DashboardErrorCode;
import com.backend.meety.domain.dashboard.exception.DashboardException;
import com.backend.meety.domain.dashboard.service.DashboardService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DashboardControllerTest {

    private DashboardService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(DashboardService.class);
        mvc = MockMvcBuilders.standaloneSetup(new DashboardController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("팀 대시보드는 공통 응답 구조로 반환한다")
    void getDashboardReturnsApiResponse() throws Exception {
        // 테스트 목적:
        // 인증된 사용자가 팀 대시보드를 조회하면
        // /api/v1/teams/{teamId}/dashboard API가 공통 성공 응답 형식을 반환하는지 검증한다.

        // given
        DashboardResponse response = new DashboardResponse(
                28,
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 10, 12),
                new DashboardSummaryResponse(
                        1L, 120L, 120L, 0L,
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        BigDecimal.valueOf(1.5), BigDecimal.valueOf(1.25)),
                List.of(new DashboardChartPointResponse(
                        LocalDate.of(2026, 9, 15), LocalDate.of(2026, 9, 18), 120L, 120L)),
                List.of(new DashboardTopicResponse("WebSocket ACK 방식", BigDecimal.valueOf(1.5)))
        );
        when(service.getDashboard(1L, 2L, 28)).thenReturn(response);

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard").param("days", "28"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.periodDays").value(28))
                .andExpect(jsonPath("$.data.startDate").value("2026-09-15"))
                .andExpect(jsonPath("$.data.summary.totalMeetings").value(1))
                .andExpect(jsonPath("$.data.summary.totalContextOutSeconds").value(1.5))
                .andExpect(jsonPath("$.data.chart[0].targetDurationSeconds").value(120))
                .andExpect(jsonPath("$.data.topics[0].name").value("WebSocket ACK 방식"))
                .andExpect(jsonPath("$.error").doesNotExist());
        verify(service).getDashboard(1L, 2L, 28);
    }

    @Test
    @DisplayName("days를 생략하면 28일 대시보드를 조회한다")
    void defaultsDaysToTwentyEight() throws Exception {
        // 테스트 목적:
        // 클라이언트가 days 쿼리 파라미터를 보내지 않아도
        // 기본 28일 기간으로 서비스가 호출되는지 검증한다.

        // given
        when(service.getDashboard(1L, 2L, 28)).thenReturn(emptyResponse());

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard"))
                .andExpect(status().isOk());
        verify(service).getDashboard(1L, 2L, 28);
    }

    @Test
    @DisplayName("잘못된 days 요청은 공통 실패 응답으로 반환한다")
    void invalidDaysReturnsBadRequest() throws Exception {
        // 테스트 목적:
        // 지원하지 않는 기간을 요청한 경우
        // Controller가 공통 Error Response 형식으로 400 응답을 반환하는지 검증한다.

        // given
        when(service.getDashboard(1L, 2L, 13))
                .thenThrow(new DashboardException(DashboardErrorCode.INVALID_DASHBOARD_PERIOD));

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard").param("days", "13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INVALID_DASHBOARD_PERIOD"));
    }

    private DashboardResponse emptyResponse() {
        return new DashboardResponse(
                28,
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 10, 12),
                new DashboardSummaryResponse(
                        0L, 0L, 0L, 0L,
                        BigDecimal.ZERO.setScale(2), BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO, BigDecimal.ZERO.setScale(2)),
                List.of(),
                List.of()
        );
    }
}
