package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.client.ChatAiClient;
import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.client.ChatAiResponse;
import com.backend.meety.domain.ai.event.ChatRequestedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.task.TaskExecutionAutoConfiguration;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.client.RestClientResponseException;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatAnswerRequestListener {

    private final ChatProcessingService chatProcessingService;
    private final ChatAiClient chatAiClient;

    @Async(TaskExecutionAutoConfiguration.APPLICATION_TASK_EXECUTOR_BEAN_NAME)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void requestAnswer(ChatRequestedEvent event) {
        chatProcessingService.startProcessing(event.aiRequestId())
                .ifPresent(this::callAi);
    }

    private void callAi(ChatAiRequest request) {
        ChatAiResponse response;
        try {
            response = chatAiClient.requestAnswer(request);
        } catch (Exception e) {
            logFailure(request.aiRequestId(), e);
            chatProcessingService.fail(request.teamId(), request.aiRequestId());
            return;
        }
        if (!hasAnswer(response)) {
            log.warn("AI 챗봇 응답에 답변이 없습니다. aiRequestId={}", request.aiRequestId());
            chatProcessingService.fail(request.teamId(), request.aiRequestId());
            return;
        }
        chatProcessingService.complete(request.teamId(), request.aiRequestId(), response.answer());
    }

    private boolean hasAnswer(ChatAiResponse response) {
        return response != null && response.answer() != null && !response.answer().isBlank();
    }

    private void logFailure(Long aiRequestId, Exception e) {
        if (e instanceof RestClientResponseException responseError) {
            log.warn("AI 챗봇이 실패 응답을 보냈습니다. aiRequestId={}, status={}, body={}",
                    aiRequestId, responseError.getStatusCode(), responseError.getResponseBodyAsString());
            return;
        }
        log.warn("AI 챗봇 호출에 실패했습니다. aiRequestId={}", aiRequestId, e);
    }
}
