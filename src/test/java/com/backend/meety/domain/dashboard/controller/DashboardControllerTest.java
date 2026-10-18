package com.backend.meety.domain.dashboard.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.dashboard.dto.DashboardChartPointResponse;
import com.backend.meety.domain.dashboard.dto.DashboardMeetingResponse;
import com.backend.meety.domain.dashboard.dto.DashboardResponse;
import com.backend.meety.domain.dashboard.dto.DashboardSummaryResponse;
import com.backend.meety.domain.dashboard.dto.DashboardTopicResponse;
import com.backend.meety.domain.dashboard.exception.DashboardErrorCode;
import com.backend.meety.domain.dashboard.exception.DashboardException;
import com.backend.meety.domain.dashboard.service.DashboardService;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class DashboardControllerTest {

    private static final LocalDate DATE = LocalDate.of(2026, 10, 12);
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
    @DisplayName("일간 대시보드는 새 명세의 요약·차트·회의 목록을 공통 응답으로 반환한다")
    void getDashboardReturnsApiResponse() throws Exception {
        // 테스트 목적:
        // 인증된 사용자가 팀 대시보드를 조회하면
        // /api/v1/teams/{teamId}/dashboard API가 공통 성공 응답 형식을 반환하는지 검증한다.

        // given
        DashboardResponse response = new DashboardResponse(
                1,
                DATE,
                DATE,
                new DashboardSummaryResponse(
                        1L, 120L, 120L, 0L,
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.valueOf(1.5), BigDecimal.valueOf(1.25)),
                List.of(new DashboardChartPointResponse(
                        DATE, DATE, 1L, 120L, 120L, 0L)),
                List.of(new DashboardMeetingResponse(101L, "데일리 스크럼", 120L, 120L)),
                List.of(new DashboardTopicResponse("WebSocket ACK 방식", BigDecimal.valueOf(1.5)))
        );
        when(service.getDashboard(1L, 2L, DATE, DATE)).thenReturn(response);

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard").param("from", DATE.toString()).param("to", DATE.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.periodDays").value(1))
                .andExpect(jsonPath("$.data.startDate").value("2026-10-12"))
                .andExpect(jsonPath("$.data.endDate").value("2026-10-12"))
                .andExpect(jsonPath("$.data.summary.totalMeetings").value(1))
                .andExpect(jsonPath("$.data.summary.totalContextOutSeconds").value(1.5))
                .andExpect(jsonPath("$.data.summary.overtimeDurationRate").doesNotExist())
                .andExpect(jsonPath("$.data.chart.length()").value(1))
                .andExpect(jsonPath("$.data.chart[0].meetingCount").value(1))
                .andExpect(jsonPath("$.data.chart[0].targetDurationSeconds").value(120))
                .andExpect(jsonPath("$.data.chart[0].actualDurationSeconds").value(120))
                .andExpect(jsonPath("$.data.chart[0].overtimeMeetings").value(0))
                .andExpect(jsonPath("$.data.meetings[0].meetingId").value(101))
                .andExpect(jsonPath("$.data.meetings[0].title").value("데일리 스크럼"))
                .andExpect(jsonPath("$.data.meetings[0].targetDurationSeconds").value(120))
                .andExpect(jsonPath("$.data.meetings[0].actualDurationSeconds").value(120))
                .andExpect(jsonPath("$.data.topics[0].name").value("WebSocket ACK 방식"))
                .andExpect(jsonPath("$.error").doesNotExist());
        verify(service).getDashboard(1L, 2L, DATE, DATE);
    }

    @Test
    @DisplayName("주간 대시보드의 meetings는 생략하지 않고 빈 배열로 반환한다")
    void weeklyMeetingsAreEmptyArray() throws Exception {
        // 테스트 목적:
        // 정상 월~일 주간 조회는 날짜별 차트 7개와
        // null이 아닌 빈 meetings 배열을 반환하는지 검증한다.

        // given
        when(service.getDashboard(1L, 2L, DATE, DATE.plusDays(6))).thenReturn(emptyResponse());

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard").param("from", "2026-10-12").param("to", "2026-10-18"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.periodDays").value(7))
                .andExpect(jsonPath("$.data.chart.length()").value(7))
                .andExpect(jsonPath("$.data.meetings").isArray())
                .andExpect(jsonPath("$.data.meetings").isEmpty());
        verify(service).getDashboard(1L, 2L, DATE, DATE.plusDays(6));
    }

    @Test
    @DisplayName("파싱 가능한 날짜의 잘못된 기간은 INVALID_DASHBOARD_PERIOD로 반환한다")
    void invalidPeriodReturnsBadRequest() throws Exception {
        // 테스트 목적:
        // 지원하지 않는 기간을 요청한 경우
        // Controller가 공통 Error Response 형식으로 400 응답을 반환하는지 검증한다.

        // given
        when(service.getDashboard(1L, 2L, DATE, DATE.plusDays(1)))
                .thenThrow(new DashboardException(DashboardErrorCode.INVALID_DASHBOARD_PERIOD));

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard").param("from", "2026-10-12").param("to", "2026-10-13"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INVALID_DASHBOARD_PERIOD"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"from", "to", "both", "days"})
    @DisplayName("from 또는 to가 누락되면 기존 전역 규칙인 INVALID_INPUT_VALUE를 반환한다")
    void missingDatesReturnInvalidInput(String missing) throws Exception {
        // 테스트 목적:
        // 날짜 누락 및 기존 days만 사용하는 요청이
        // 기본 기간으로 처리되지 않고 입력 오류로 거절되는지 검증한다.

        // given
        MockHttpServletRequestBuilder request = get("/api/v1/teams/2/dashboard");
        if ("from".equals(missing)) {
            request.param("to", "2026-10-12");
        } else if ("to".equals(missing)) {
            request.param("from", "2026-10-12");
        } else if ("days".equals(missing)) {
            request.param("days", "28");
        }

        // when, then
        mvc.perform(request)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({
            "2026/10/12, 2026-10-12",
            "2026-10-12, not-a-date",
            "2026-02-30, 2026-02-30",
            "2026-13-01, 2026-13-01",
            "2026-10-12T00:00:00, 2026-10-12",
            "'', 2026-10-12",
            "2026-10-12, ''"
    })
    @DisplayName("날짜 형식 오류와 존재하지 않는 날짜는 INVALID_INPUT_VALUE를 반환한다")
    void malformedDatesReturnInvalidInput(String from, String to) throws Exception {
        // 테스트 목적:
        // ISO 날짜로 변환할 수 없는 입력이 서비스에 도달하지 않고
        // 기존 전역 예외 처리 형식으로 거절되는지 검증한다.

        // given

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard").param("from", from).param("to", to))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        verifyNoInteractions(service);
    }

    @ParameterizedTest
    @CsvSource({"TEAM_ACCESS_DENIED, 403", "TEAM_NOT_FOUND, 404"})
    @DisplayName("팀 접근 거부와 없는 팀은 기존 팀 오류 응답을 유지한다")
    void teamErrorsKeepExistingContract(TeamErrorCode errorCode, int statusCode) throws Exception {
        // 테스트 목적:
        // 팀 대시보드의 접근 거부 및 팀 없음 오류가
        // 날짜 파라미터 전환 후에도 동일한 HTTP 상태와 오류 코드로 반환되는지 검증한다.

        // given
        when(service.getDashboard(1L, 2L, DATE, DATE)).thenThrow(new TeamException(errorCode));

        // when, then
        mvc.perform(get("/api/v1/teams/2/dashboard").param("from", DATE.toString()).param("to", DATE.toString()))
                .andExpect(status().is(statusCode))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value(errorCode.getCode()));
    }

    private DashboardResponse emptyResponse() {
        return new DashboardResponse(
                7,
                DATE,
                DATE.plusDays(6),
                new DashboardSummaryResponse(
                        0L, 0L, 0L, 0L,
                        BigDecimal.ZERO.setScale(2),
                        BigDecimal.ZERO, BigDecimal.ZERO.setScale(2)),
                DATE.datesUntil(DATE.plusDays(7))
                        .map(date -> new DashboardChartPointResponse(date, date, 0L, 0L, 0L, 0L))
                        .toList(),
                List.of(),
                List.of()
        );
    }
}
