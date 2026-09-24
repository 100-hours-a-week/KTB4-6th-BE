package com.backend.meety.domain.transcript.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.service.TranscriptService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TranscriptControllerTest {

    private TranscriptService transcriptService;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        transcriptService = mock(TranscriptService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TranscriptController(transcriptService))
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
    void getTranscriptsReturnsCommonSuccessResponse() throws Exception {
        when(transcriptService.getTranscripts(1L, 100L))
                .thenReturn(List.of(new TranscriptSegmentResponse(
                        900L,
                        null,
                        null,
                        null,
                        1L,
                        "hello",
                        1000L,
                        2000L,
                        LocalDateTime.of(2026, 9, 21, 5, 30)
                )));

        mvc.perform(get("/api/v1/meetings/100/transcripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].segmentId").value(900L))
                .andExpect(jsonPath("$.data[0].sequenceNumber").value(1L))
                .andExpect(jsonPath("$.data[0].content").value("hello"))
                .andExpect(jsonPath("$.error").doesNotExist());

        verify(transcriptService).getTranscripts(1L, 100L);
    }

    @Test
    void getTranscriptsReturnsEmptyList() throws Exception {
        when(transcriptService.getTranscripts(1L, 100L)).thenReturn(List.of());

        mvc.perform(get("/api/v1/meetings/100/transcripts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty())
                .andExpect(jsonPath("$.error").doesNotExist());
    }

    @Test
    void getTranscriptsReturnsMeetingNotFound() throws Exception {
        when(transcriptService.getTranscripts(1L, 404L))
                .thenThrow(new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));

        mvc.perform(get("/api/v1/meetings/404/transcripts"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("MEETING_NOT_FOUND"));
    }

    @Test
    void getTranscriptsReturnsMeetingAccessDenied() throws Exception {
        when(transcriptService.getTranscripts(1L, 100L))
                .thenThrow(new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED));

        mvc.perform(get("/api/v1/meetings/100/transcripts"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.error.code").value("MEETING_ACCESS_DENIED"));
    }
}
