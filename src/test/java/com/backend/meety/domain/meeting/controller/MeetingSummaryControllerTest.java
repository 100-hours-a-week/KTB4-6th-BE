package com.backend.meety.domain.meeting.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.meeting.dto.SummaryCreateResponse;
import com.backend.meety.domain.meeting.dto.SummaryDetailResponse;
import com.backend.meety.domain.meeting.exception.SummaryErrorCode;
import com.backend.meety.domain.meeting.exception.SummaryException;
import com.backend.meety.domain.meeting.service.MeetingSummaryService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
    @DisplayName("요약 조회는 200과 최신 회차를 반환한다")
    void getLatestSummaryReturnsOk() throws Exception {
        when(service.getLatestSummary(1L, 100L)).thenReturn(new SummaryDetailResponse(
                501L, "## 회의 요약", 1L, AiRequestStatus.COMPLETED, null,
                LocalDateTime.of(2026, 8, 25, 13, 5, 22)));

        mvc.perform(get("/api/v1/meetings/100/summaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.summaryId").value(501))
                .andExpect(jsonPath("$.data.content").value("## 회의 요약"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.createdAt").exists());
    }

    @Test
    @DisplayName("요약이 없으면 404를 반환한다")
    void noSummaryReturnsNotFound() throws Exception {
        when(service.getLatestSummary(1L, 100L))
                .thenThrow(new SummaryException(SummaryErrorCode.SUMMARY_NOT_FOUND));

        mvc.perform(get("/api/v1/meetings/100/summaries"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("SUMMARY_NOT_FOUND"));
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 서버가 UUID를 만들어 채운다")
    void missingIdempotencyKeyIsGenerated() throws Exception {
        when(service.requestSummary(eq(1L), eq(100L), anyString())).thenReturn(
                new SummaryCreateResponse(502L, 2L, AiRequestStatus.ACCEPTED, 266L));

        mvc.perform(post("/api/v1/meetings/100/summaries"))
                .andExpect(status().isAccepted());

        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(service).requestSummary(eq(1L), eq(100L), captor.capture());
        assertThat(UUID.fromString(captor.getValue())).isNotNull();
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
