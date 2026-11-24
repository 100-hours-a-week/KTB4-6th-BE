package com.backend.meety.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.ai.client.ChatAiCitation;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class ChatResultHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ChatProcessingService chatProcessingService = mock(ChatProcessingService.class);
    private final ChatResultHandler handler = new ChatResultHandler(chatProcessingService, objectMapper);

    @Test
    @DisplayName("답변과 근거를 저장한다")
    void completesAnswer() {
        boolean accepted = handler.handleSucceeded(900L, objectMapper.readTree("""
                {"aiRequestId":900,"answer":"금요일 배포입니다","citations":[{"sourceType":"transcript","meetingId":100,"segmentId":801}]}
                """));

        assertThat(accepted).isTrue();
        verify(chatProcessingService).completeAnswer(
                900L, "금요일 배포입니다", List.of(new ChatAiCitation("transcript", 100L, 801L, null)));
    }

    @Test
    @DisplayName("답변이 비어 있으면 결과를 거절한다")
    void rejectsBlankAnswer() {
        boolean accepted = handler.handleSucceeded(900L, objectMapper.readTree("{\"answer\":\" \"}"));

        assertThat(accepted).isFalse();
        verify(chatProcessingService, never()).completeAnswer(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("최종 실패면 크레딧 복구·실패 알림을 맡긴다")
    void failsAnswer() {
        handler.handleFinalFailure(900L);

        verify(chatProcessingService).failAnswer(900L);
    }

    @Test
    @DisplayName("챗봇은 재시도하지 않는다")
    void doesNotRetry() {
        assertThat(handler.maxRetryCount()).isEqualTo(1L);
    }
}
