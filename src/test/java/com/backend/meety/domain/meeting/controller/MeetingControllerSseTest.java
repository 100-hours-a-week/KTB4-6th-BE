package com.backend.meety.domain.meeting.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.meeting.realtime.MeetingSseService;
import com.backend.meety.domain.meeting.service.MeetingParticipantService;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MeetingControllerSseTest {

    private MeetingSseService sseService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        sseService = mock(MeetingSseService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MeetingController(
                        mock(MeetingService.class),
                        mock(MeetingParticipantService.class),
                        sseService))
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
    void connectEventsUsesAuthenticatedUserAndReturnsSseEmitter() throws Exception {
        SseEmitter emitter = new SseEmitter();
        when(sseService.connect(1L, 100L)).thenReturn(emitter);

        mvc.perform(get("/api/v1/meetings/100/events").accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(status().isOk())
                .andExpect(request().asyncStarted());

        verify(sseService).connect(1L, 100L);
    }
}
