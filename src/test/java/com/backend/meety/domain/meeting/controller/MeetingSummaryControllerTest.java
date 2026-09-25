package com.backend.meety.domain.meeting.controller;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.meeting.dto.SummaryCreateResponse;
import com.backend.meety.domain.meeting.exception.SummaryErrorCode;
import com.backend.meety.domain.meeting.exception.SummaryException;
import com.backend.meety.domain.meeting.service.MeetingSummaryService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
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
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

class MeetingSummaryControllerTest {

    private static final String IDEMPOTENCY_KEY = "11111111-2222-3333-4444-555555555555";

    private MeetingSummaryService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MeetingSummaryService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mvc = MockMvcBuilders.standaloneSetup(new MeetingSummaryController(service))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(1L, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("요약 재생성은 202와 접수 정보를 반환한다")
    void regenerateReturnsAccepted() throws Exception {
        when(service.requestSummary(1L, 100L, IDEMPOTENCY_KEY)).thenReturn(
                new SummaryCreateResponse(502L, 2L, AiRequestStatus.ACCEPTED, 266L));

        mvc.perform(post("/api/v1/meetings/100/summaries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summaryId").value(502))
                .andExpect(jsonPath("$.data.version").value(2))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.creditBalance").value(266));

        verify(service).requestSummary(1L, 100L, IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 400이다")
    void missingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/api/v1/meetings/100/summaries"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(service);
    }

    @Test
    @DisplayName("전사가 없으면 409를 반환한다")
    void emptyTranscriptReturnsConflict() throws Exception {
        when(service.requestSummary(1L, 100L, IDEMPOTENCY_KEY))
                .thenThrow(new SummaryException(SummaryErrorCode.TRANSCRIPT_EMPTY));

        mvc.perform(post("/api/v1/meetings/100/summaries")
                        .header("Idempotency-Key", IDEMPOTENCY_KEY))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRANSCRIPT_EMPTY"));
    }
}
