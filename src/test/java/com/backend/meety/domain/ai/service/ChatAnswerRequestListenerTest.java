package com.backend.meety.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.ChatAiClient;
import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.client.ChatAiResponse;
import com.backend.meety.domain.ai.event.ChatRequestedEvent;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class ChatAnswerRequestListenerTest {

    private static final ChatRequestedEvent EVENT = new ChatRequestedEvent(900L, 100L, 8801L, 9L);
    private static final ChatAiRequest REQUEST = new ChatAiRequest(900L, 100L, 2L, "질문", List.of(), List.of());

    private final ChatProcessingService processingService = mock(ChatProcessingService.class);
    private final ChatAiClient client = mock(ChatAiClient.class);
    private final ChatAnswerRequestListener listener = new ChatAnswerRequestListener(processingService, client);

    @Test
    @DisplayName("AI가 답변하면 답변 저장을 요청한다")
    void completesWithAnswer() {
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(REQUEST));
        when(client.requestAnswer(REQUEST)).thenReturn(new ChatAiResponse(900L, "답변", List.of()));

        listener.requestAnswer(EVENT);

        verify(processingService).complete(2L, 900L, "답변");
        verify(processingService, never()).fail(anyLong(), anyLong());
    }

    @Test
    @DisplayName("처리를 시작하지 못한 질문은 AI를 호출하지 않는다")
    void skipsWhenNotStarted() {
        when(processingService.startProcessing(900L)).thenReturn(Optional.empty());

        listener.requestAnswer(EVENT);

        verify(client, never()).requestAnswer(any());
    }

    @Test
    @DisplayName("AI 호출이 타임아웃이면 예외를 밖으로 던지지 않고 실패 처리한다")
    void failsOnTimeout() {
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(REQUEST));
        when(client.requestAnswer(REQUEST)).thenThrow(new ResourceAccessException("Read timed out"));

        assertThatCode(() -> listener.requestAnswer(EVENT)).doesNotThrowAnyException();
        verify(processingService).fail(2L, 900L);
    }

    @Test
    @DisplayName("AI가 실패 응답을 보내면 실패 처리한다")
    void failsOnErrorResponse() {
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(REQUEST));
        when(client.requestAnswer(REQUEST)).thenThrow(HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "Bad Gateway", HttpHeaders.EMPTY,
                "{\"code\":\"generation_failed\"}".getBytes(), null));

        listener.requestAnswer(EVENT);

        verify(processingService).fail(2L, 900L);
    }

    @Test
    @DisplayName("답변이 비어 있으면 실패 처리한다")
    void failsOnBlankAnswer() {
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(REQUEST));
        when(client.requestAnswer(REQUEST)).thenReturn(new ChatAiResponse(900L, " ", List.of()));

        listener.requestAnswer(EVENT);

        verify(processingService).fail(2L, 900L);
        verify(processingService, never()).complete(anyLong(), anyLong(), any());
    }
}
