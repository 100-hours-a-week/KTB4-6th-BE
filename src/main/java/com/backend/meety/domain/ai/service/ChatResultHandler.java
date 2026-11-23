package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.client.ChatAiResponse;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.messaging.AiResultHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatResultHandler implements AiResultHandler {

    private static final long MAX_RETRY_COUNT = 1L;

    private final ChatProcessingService chatProcessingService;
    private final ObjectMapper objectMapper;

    @Override
    public AiRequestType requestType() {
        return AiRequestType.CHAT;
    }

    @Override
    public boolean handleSucceeded(Long aiRequestId, JsonNode result) {
        ChatAiResponse response;
        try {
            response = objectMapper.treeToValue(result, ChatAiResponse.class);
        } catch (JacksonException e) {
            log.error("AI 챗봇 결과를 해석할 수 없습니다. aiRequestId={}", aiRequestId, e);
            return false;
        }
        if (response.answer() == null || response.answer().isBlank()) {
            log.warn("AI 챗봇 응답에 답변이 없습니다. aiRequestId={}", aiRequestId);
            return false;
        }
        chatProcessingService.completeAnswer(aiRequestId, response.answer(), response.citations());
        return true;
    }

    @Override
    public void handleFinalFailure(Long aiRequestId) {
        chatProcessingService.failAnswer(aiRequestId);
    }

    @Override
    public long maxRetryCount() {
        return MAX_RETRY_COUNT;
    }
}
