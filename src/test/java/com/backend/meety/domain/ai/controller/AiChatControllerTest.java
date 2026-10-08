package com.backend.meety.domain.ai.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.backend.meety.domain.ai.dto.ChatCreateResponse;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.ChatInputType;
import com.backend.meety.domain.ai.service.AiChatService;
import com.backend.meety.global.exception.GlobalExceptionHandler;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AiChatControllerTest {

    private static final String KEY = "11111111-2222-3333-4444-555555555555";

    private AiChatService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(AiChatService.class);
        mvc = MockMvcBuilders.standaloneSetup(new AiChatController(service))
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
    @DisplayName("질문을 접수하면 202와 메시지 ID·상태·잔액을 반환한다")
    void acceptsQuestion() throws Exception {
        when(service.requestChat(1L, 100L, KEY, ChatInputType.TEXT, "질문"))
                .thenReturn(new ChatCreateResponse(8801L, AiRequestStatus.ACCEPTED, 269L));

        mvc.perform(post("/api/v1/meetings/100/chats").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"질문\",\"inputType\":\"TEXT\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messageId").value(8801))
                .andExpect(jsonPath("$.data.status").value("ACCEPTED"))
                .andExpect(jsonPath("$.data.creditBalance").value(269));
    }

    @Test
    @DisplayName("질문이 비었거나 1000자를 넘으면 INVALID_QUESTION_LENGTH로 거절한다")
    void rejectsInvalidQuestionLength() throws Exception {
        for (String question : List.of("", "가".repeat(1001))) {
            mvc.perform(post("/api/v1/meetings/100/chats").header("Idempotency-Key", KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"question\":\"" + question + "\",\"inputType\":\"TEXT\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_QUESTION_LENGTH"));
        }
        verify(service, never()).requestChat(anyLong(), anyLong(), anyString(), any(), anyString());
    }

    @Test
    @DisplayName("inputType이 TEXT·VOICE가 아니면 INVALID_INPUT_TYPE으로 거절한다")
    void rejectsInvalidInputType() throws Exception {
        mvc.perform(post("/api/v1/meetings/100/chats").header("Idempotency-Key", KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"질문\",\"inputType\":\"IMAGE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_TYPE"));
    }

    @Test
    @DisplayName("Idempotency-Key 헤더가 없으면 INVALID_INPUT_VALUE로 거절한다")
    void rejectsMissingIdempotencyKey() throws Exception {
        mvc.perform(post("/api/v1/meetings/100/chats")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"question\":\"질문\",\"inputType\":\"TEXT\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_INPUT_VALUE"));
    }
}
