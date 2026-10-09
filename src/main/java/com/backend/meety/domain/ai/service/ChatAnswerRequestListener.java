package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.client.ChatAiClient;
import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.client.ChatAiResponse;
import com.backend.meety.domain.ai.event.ChatRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAnswerRequestListener {

    private final ChatProcessingService chatProcessingService;
    private final ChatAiClient chatAiClient;

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void requestAnswer(ChatRequestedEvent event) {
        chatProcessingService.startProcessing(event.aiRequestId())
                .ifPresent(this::callAi);
    }

    private void callAi(ChatAiRequest request) {
        try {
            ChatAiResponse response = chatAiClient.requestAnswer(request);
            log.info("AI 챗봇 답변을 받았습니다. aiRequestId={}, citationCount={}",
                    request.aiRequestId(), response.citations() == null ? 0 : response.citations().size());
        } catch (Exception e) {
            log.warn("AI 챗봇 호출에 실패했습니다. aiRequestId={}", request.aiRequestId(), e);
        }
    }
}
