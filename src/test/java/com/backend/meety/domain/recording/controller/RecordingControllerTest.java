package com.backend.meety.domain.recording.controller;

import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.exception.CreditException;
import com.backend.meety.domain.meeting.controller.MeetingController;
import com.backend.meety.domain.meeting.realtime.MeetingSseService;
import com.backend.meety.domain.meeting.service.MeetingParticipantService;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.recording.dto.RecordingSessionResponse;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.exception.RecordingException;
import com.backend.meety.domain.recording.service.RecordingService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RecordingControllerTest {

    private RecordingService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RecordingService.class);
        mvc = MockMvcBuilders.standaloneSetup(new RecordingController(service),
                        new MeetingController(
                                mock(MeetingService.class),
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
    void startAcceptsNoBodyAndUsesAuthenticatedUser() throws Exception {
        when(service.start(1L, 100L)).thenReturn(response(RecordingSessionStatus.RECORDING));
        mvc.perform(post("/api/v1/meetings/100/recordings"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.error").isEmpty())
                .andExpect(jsonPath("$.data.recordingSessionId").value(700))
                .andExpect(jsonPath("$.data.meetingId").value(100))
                .andExpect(jsonPath("$.data.startedByTeamMemberId").value(10))
                .andExpect(jsonPath("$.data.status").value("RECORDING"))
                .andExpect(jsonPath("$.data.startedAt").value("2026-09-18T14:20:00"))
                .andExpect(jsonPath("$.data.autoEndAt").value("2026-09-18T15:50:00"))
                .andExpect(jsonPath("$.data.pausedAt").isEmpty())
                .andExpect(jsonPath("$.data.endedAt").isEmpty())
                .andExpect(jsonPath("$.data.createdAt").doesNotExist());
        verify(service).start(1L, 100L);
    }

    @Test
    void getActiveReturnsCommonResponse() throws Exception {
        when(service.getActive(1L, 100L)).thenReturn(response(RecordingSessionStatus.PAUSED));
        mvc.perform(get("/api/v1/meetings/100/recording"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value("PAUSED"));
    }

    @Test
    void missingActiveRecordingReturns404() throws Exception {
        when(service.getActive(1L, 100L))
                .thenThrow(new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        mvc.perform(get("/api/v1/meetings/100/recording"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error.code").value("RECORDING_SESSION_NOT_FOUND"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "RECORDING", "COMPLETED"})
    void patchAcceptsSupportedStatuses(String statusValue) throws Exception {
        RecordingSessionStatus target = RecordingSessionStatus.valueOf(statusValue);
        when(service.updateStatus(1L, 700L, target)).thenReturn(response(target));
        mvc.perform(patch("/api/v1/recordings/700").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"" + statusValue + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.status").value(statusValue));
        verify(service).updateStatus(1L, 700L, target);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"status\":\"PREPARING\"}", "{\"status\":\"FAILED\"}",
            "{\"status\":\"UNKNOWN\"}", "{\"status\":\"paused\"}", "{\"status\":\"\"}",
            "{\"status\":null}", "{}", "{\"status\":20}"})
    void patchRejectsInvalidStatusWithSpecifiedCode(String body) throws Exception {
        mvc.perform(patch("/api/v1/recordings/700").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_RECORDING_STATUS"));
        verifyNoInteractions(service);
    }

    @Test
    void patchRejectsMalformedJsonUsingExistingHandler() throws Exception {
        mvc.perform(patch("/api/v1/recordings/700").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
        verifyNoInteractions(service);
    }

    @Test
    void existingMeetingValidationKeepsItsErrorCode() throws Exception {
        mvc.perform(post("/api/v1/teams/2/meetings").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }

    @Test
    void patchOwnerFailureIs403() throws Exception {
        when(service.updateStatus(1L, 700L, RecordingSessionStatus.PAUSED))
                .thenThrow(new RecordingException(RecordingErrorCode.RECORDING_OWNER_REQUIRED));
        mvc.perform(patch("/api/v1/recordings/700").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("RECORDING_OWNER_REQUIRED"));
    }

    @Test
    void repeatedCompleteIs409() throws Exception {
        when(service.updateStatus(1L, 700L, RecordingSessionStatus.COMPLETED))
                .thenThrow(new RecordingException(RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION));
        mvc.perform(patch("/api/v1/recordings/700").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INVALID_RECORDING_STATUS_TRANSITION"));
    }

    @Test
    void missingTeamCreditIs500() throws Exception {
        when(service.start(1L, 100L)).thenThrow(new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND));
        mvc.perform(post("/api/v1/meetings/100/recordings"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("TEAM_CREDIT_NOT_FOUND"));
    }

    private RecordingSessionResponse response(RecordingSessionStatus state) {
        return new RecordingSessionResponse(700L, 100L, 10L, state, NOW,
                state == RecordingSessionStatus.PAUSED ? NOW : null,
                state == RecordingSessionStatus.COMPLETED ? NOW : null, NOW.plusMinutes(90));
    }
}
