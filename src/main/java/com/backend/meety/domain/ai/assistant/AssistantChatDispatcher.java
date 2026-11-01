package com.backend.meety.domain.ai.assistant;

import com.backend.meety.domain.ai.assistant.dto.AssistantChatTurnResponse;
import com.backend.meety.global.config.AsyncConfig;
import tools.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class AssistantChatDispatcher {

    private final AssistantChatService service;
    private final AssistantChatAiClient aiClient;
    private final AssistantChatSseEmitterRegistry emitters;
    private final AssistantChatProperties properties;
    private final Executor executor;
    private final Set<Long> scheduled = ConcurrentHashMap.newKeySet();

    public AssistantChatDispatcher(AssistantChatService service, AssistantChatAiClient aiClient,
                                   AssistantChatSseEmitterRegistry emitters, AssistantChatProperties properties,
                                   @Qualifier(AsyncConfig.CHAT_TASK_EXECUTOR_BEAN_NAME) Executor executor) {
        this.service = service;
        this.aiClient = aiClient;
        this.emitters = emitters;
        this.properties = properties;
        this.executor = executor;
    }

    public void dispatch(Long requestId) {
        if (!scheduled.add(requestId)) return;
        try {
            executor.execute(() -> {
                try {
                    process(requestId);
                } finally {
                    scheduled.remove(requestId);
                }
            });
        } catch (RuntimeException rejected) {
            scheduled.remove(requestId);
            log.warn("전역 채팅 실행기에서 요청을 접수하지 못했습니다. aiRequestId={}", requestId);
        }
    }

    @Scheduled(fixedDelayString = "${ai.global-chat.dispatch-delay:1s}")
    public void dispatchAccepted() {
        service.dispatchAccepted(this);
    }

    @Scheduled(fixedDelayString = "${ai.global-chat.stale-check-delay:10s}")
    public void failStaleProcessing() {
        var failedRequestIds = service.failStaleProcessing();
        failedRequestIds.forEach(requestId -> emitters.complete(requestId, "failed", Map.of(
                "aiRequestId", requestId,
                "code", "timeout",
                "message", "답변 생성 시간이 초과되었습니다.")));
        if (!failedRequestIds.isEmpty()) {
            log.warn("시간이 초과된 전역 채팅 요청을 실패 처리했습니다. count={}", failedRequestIds.size());
        }
    }

    private void process(Long requestId) {
        AssistantChatWork work;
        try {
            work = service.claim(requestId);
        } catch (RuntimeException e) {
            log.warn("전역 채팅 요청을 선점하지 못했습니다. aiRequestId={}", requestId, e);
            return;
        }
        if (work == null) return;
        emitters.send(requestId, "status", Map.of("aiRequestId", requestId, "status", "PROCESSING"));
        try {
            AssistantChatAiResult result = aiClient.stream(
                    new AssistantChatAiRequest(work.requestId(), work.teamId(), work.question(),
                            work.recentMessages()),
                    event -> forward(requestId, event));
            AssistantChatTurnResponse completed = service.complete(requestId, work.processingToken(),
                    result.answer(), result.meetingDraft(), result.referencedMeetingIds());
            if (completed != null) {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("aiRequestId", requestId);
                payload.put("answer", completed.answer());
                payload.put("referencedMeetingIds", result.referencedMeetingIds());
                if (completed.meetingDraft() != null) payload.put("meetingDraft", completed.meetingDraft());
                emitters.complete(requestId, "completed", payload);
            }
        } catch (Exception e) {
            String code = e instanceof AssistantChatAiException aiError ? aiError.failureCode() : "generation_failed";
            boolean failed = service.fail(requestId, work.processingToken(), code);
            if (failed) {
                emitters.complete(requestId, "failed", Map.of(
                        "aiRequestId", requestId,
                        "code", code,
                        "message", "답변을 생성하지 못했습니다. 다시 시도해주세요."));
            }
            log.warn("전역 채팅 AI 처리가 실패했습니다. aiRequestId={}, code={}, exceptionType={}",
                    requestId, code, e.getClass().getSimpleName());
        }
    }

    private void forward(Long requestId, AssistantChatAiEvent event) {
        if (event == null || event.name() == null) return;
        JsonNode data = event.data();
        if ("status".equals(event.name()) && data != null && data.path("phase").isTextual()) {
            emitters.send(requestId, "status", Map.of("phase", data.path("phase").asText()));
        } else if ("delta".equals(event.name()) && data != null && data.path("text").isTextual()) {
            emitters.send(requestId, "delta", Map.of("text", data.path("text").asText()));
        } else if ("ping".equals(event.name())) {
            emitters.send(requestId, "ping", Map.of());
        }
    }
}
