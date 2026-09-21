package com.backend.meety.domain.meeting.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.meeting.dto.MeetingInProgressResponse;
import com.backend.meety.domain.meeting.realtime.MeetingSseService;
import com.backend.meety.domain.meeting.service.MeetingParticipantService;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
}
