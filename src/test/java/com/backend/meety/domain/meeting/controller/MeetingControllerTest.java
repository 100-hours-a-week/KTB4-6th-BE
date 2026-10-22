package com.backend.meety.domain.meeting.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.meeting.dto.MeetingInProgressResponse;
import com.backend.meety.domain.meeting.dto.MeetingCreateRequest;
import com.backend.meety.domain.meeting.dto.MeetingCreateResponse;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.realtime.MeetingSseService;
import com.backend.meety.domain.meeting.service.MeetingParticipantService;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.util.List;
import java.time.LocalDateTime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class MeetingControllerTest {

    private MeetingService meetingService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        meetingService = mock(MeetingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MeetingController(
                        meetingService,
                        mock(MeetingParticipantService.class),
                        mock(MeetingSseService.class)))
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
    void hasInProgressMeetingReturnsCommonSuccessResponse() throws Exception {
        when(meetingService.hasInProgressMeeting(1L, 2L))
                .thenReturn(new MeetingInProgressResponse(true));

        mvc.perform(get("/api/v1/teams/2/meetings/in-progress"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.hasInProgressMeeting").value(true))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(meetingService).hasInProgressMeeting(1L, 2L);
    }

    @Test
    void hasInProgressMeetingReturnsTeamAccessDenied() throws Exception {
        when(meetingService.hasInProgressMeeting(1L, 2L))
                .thenThrow(new TeamException(TeamErrorCode.TEAM_ACCESS_DENIED));

        mvc.perform(get("/api/v1/teams/2/meetings/in-progress"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("TEAM_ACCESS_DENIED"));
    }

    @Test
    void hasInProgressMeetingReturnsTeamNotFound() throws Exception {
        when(meetingService.hasInProgressMeeting(1L, 2L))
                .thenThrow(new TeamException(TeamErrorCode.TEAM_NOT_FOUND));

        mvc.perform(get("/api/v1/teams/2/meetings/in-progress"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("TEAM_NOT_FOUND"));
    }

    @Test
    @DisplayName("1글자 제목으로 회의를 생성하면 400 INVALID_INPUT_VALUE로 거절한다")
    void createMeetingRejectsOneCharacterTitle() throws Exception {
        // 테스트 목적:
        // 예정 시각 필수값 추가 후에도 잘못된 제목의 기존 오류 응답을 유지하는지 검증한다.
        // given
        String json = """
                {"title":"A","purpose":"진행 상황 공유","targetDurationMinutes":30,
                 "scheduledAt":"2026-10-22T15:00:00"}
                """;
        // when
        // then
        mvc.perform(post("/api/v1/teams/1/meetings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.error.message").value("회의 제목은 2자 이상 20자 이하로 입력해주세요."));

        verifyNoInteractions(meetingService);
    }

    @Test
    @DisplayName("생성 API는 오프셋 없는 KST 예정 시각을 변환 없이 전달하고 201로 응답한다")
    void createMeetingPreservesScheduledLocalTime() throws Exception {
        // 테스트 목적:
        // JSON의 예정 시각이 같은 LocalDateTime으로 서비스에 전달되고 기존 래퍼로 반환되는지 검증한다.
        // given
        var scheduledAt = LocalDateTime.of(2026, 10, 22, 15, 0);
        var request = new MeetingCreateRequest("주간 회의", "업무 공유", null, 30,
                List.of(), List.of("외부 참석자"), scheduledAt);
        when(meetingService.createMeeting(1L, 2L, request)).thenReturn(new MeetingCreateResponse(
                101L, 2L, 3L, request.title(), request.purpose(), null, scheduledAt, 30,
                MeetingStatus.WAITING, LocalDateTime.of(2026, 10, 22, 9, 0), List.of()));
        // when
        // then
        mvc.perform(post("/api/v1/teams/2/meetings").contentType(MediaType.APPLICATION_JSON).content("""
                        {"title":"주간 회의","purpose":"업무 공유","targetDurationMinutes":30,
                         "attendeeTeamMemberIds":[],"customAttendeeNames":["외부 참석자"],
                         "scheduledAt":"2026-10-22T15:00:00"}
                        """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.scheduledAt").value("2026-10-22T15:00:00"))
                .andExpect(jsonPath("$.data.status").value("WAITING"))
                .andExpect(jsonPath("$.error").doesNotExist());
        verify(meetingService).createMeeting(1L, 2L, request);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", ",\"scheduledAt\":null"})
    @DisplayName("예정 시각 누락 또는 null은 기존 400 INVALID_INPUT_VALUE로 반환한다")
    void scheduledAtMissingOrNull(String field) throws Exception {
        // 테스트 목적:
        // 예정 시각이 없으면 서비스 호출 없이 기존 필수값 오류 규격으로 거절하는지 검증한다.
        // given
        String json = "{\"title\":\"주간 회의\",\"purpose\":\"업무 공유\",\"targetDurationMinutes\":30" + field + "}";
        // when
        // then
        mvc.perform(post("/api/v1/teams/2/meetings").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"))
                .andExpect(jsonPath("$.error.message").value("회의 예정 시작 일시를 입력해주세요."));
        verifyNoInteractions(meetingService);
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-date", "2026-13-22T15:00:00", "2026-10-22"})
    @DisplayName("잘못된 예정 일시 문자열은 기존 JSON 파싱 오류 400으로 반환한다")
    void scheduledAtMalformed(String value) throws Exception {
        // 테스트 목적:
        // 예정 시각 형식 오류에 새로운 응답 규격 대신 기존 전역 예외 처리를 적용하는지 검증한다.
        // given
        String json = """
                {"title":"주간 회의","purpose":"업무 공유","targetDurationMinutes":30,"scheduledAt":"%s"}
                """.formatted(value);
        // when
        // then
        mvc.perform(post("/api/v1/teams/2/meetings").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        verifyNoInteractions(meetingService);
    }
}
