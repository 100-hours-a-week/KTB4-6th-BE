package com.backend.meety.domain.ai.assistant;

import com.backend.meety.domain.ai.client.AiApiPath;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
@RequiredArgsConstructor
public class RestAssistantChatAiClient implements AssistantChatAiClient {

    private final RestClient assistantChatRestClient;
    private final ObjectMapper objectMapper;
    private final AssistantChatProperties properties;

    @Override
    public AssistantChatAiResult stream(AssistantChatAiRequest request, Consumer<AssistantChatAiEvent> events) {
        long startedAt = System.nanoTime();
        try {
            return assistantChatRestClient.post()
                    .uri(AiApiPath.ASSISTANT_CHAT)
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(request)
                    .exchange((httpRequest, response) -> {
                        if (response.getStatusCode().isError()) {
                            throw new AssistantChatAiException("generation_failed",
                                    "AI assistant returned HTTP " + response.getStatusCode().value());
                        }
                        return readEvents(response.getBody(), request.aiRequestId(), startedAt, events);
                    });
        } catch (AssistantChatAiException e) {
            throw e;
        } catch (RestClientException e) {
            throw new AssistantChatAiException("timeout", "AI assistant SSE request failed", e);
        }
    }

    private AssistantChatAiResult readEvents(java.io.InputStream stream, Long expectedRequestId, long startedAt,
                                              Consumer<AssistantChatAiEvent> consumer) throws IOException {
        String eventName = "message";
        List<String> dataLines = new ArrayList<>();
        AssistantChatAiResult completed = null;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                checkDeadline(startedAt);
                if (line.isEmpty()) {
                    if (!dataLines.isEmpty()) {
                        JsonNode data = objectMapper.readTree(String.join("\n", dataLines));
                        if ("completed".equals(eventName)) {
                            if (completed == null) {
                                completed = parseCompleted(data, expectedRequestId);
                            }
                            return completed;
                        }
                        if ("failed".equals(eventName)) {
                            if (data.hasNonNull("aiRequestId")
                                    && data.path("aiRequestId").asLong(-1) != expectedRequestId) {
                                throw new AssistantChatAiException("generation_failed",
                                        "AI failure request ID mismatch");
                            }
                            String code = allowedFailureCode(data.path("code").asText(null));
                            throw new AssistantChatAiException(code, data.path("message").asText("AI request failed"));
                        }
                        consumer.accept(new AssistantChatAiEvent(eventName, data));
                    }
                    eventName = "message";
                    dataLines.clear();
                    continue;
                }
                if (line.startsWith(":")) {
                    consumer.accept(new AssistantChatAiEvent("ping", objectMapper.createObjectNode()));
                } else if (line.startsWith("event:")) {
                    eventName = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    dataLines.add(line.substring(5).stripLeading());
                }
            }
        }
        checkDeadline(startedAt);
        throw new AssistantChatAiException("generation_failed", "AI SSE stream ended without a terminal event");
    }

    private AssistantChatAiResult parseCompleted(JsonNode data, Long expectedRequestId) {
        if (data.path("aiRequestId").asLong(-1) != expectedRequestId) {
            throw new AssistantChatAiException("generation_failed", "AI completion request ID mismatch");
        }
        String answer = data.path("answer").asText();
        if (answer.isBlank()) {
            throw new AssistantChatAiException("generation_failed", "AI completion has no answer");
        }
        List<Long> referenced = new ArrayList<>();
        if (data.path("referencedMeetingIds").isArray()) {
            data.path("referencedMeetingIds").forEach(id -> {
                if (id.canConvertToLong()) referenced.add(id.asLong());
            });
        }
        JsonNode draft = data.get("meetingDraft");
        return new AssistantChatAiResult(answer, List.copyOf(referenced), draft == null || draft.isNull() ? null : draft);
    }

    private void checkDeadline(long startedAt) {
        long elapsed = System.nanoTime() - startedAt;
        if (elapsed >= properties.executionTimeout().toNanos()) {
            throw new AssistantChatAiException("execution_limit_exceeded", "AI execution limit exceeded");
        }
        if (elapsed >= properties.streamTimeout().toNanos()) {
            throw new AssistantChatAiException("timeout", "BE SSE stream timeout exceeded");
        }
    }

    private String allowedFailureCode(String code) {
        return switch (code == null ? "" : code) {
            case "invalid_request", "forbidden", "timeout", "retrieval_failed", "generation_failed",
                    "execution_limit_exceeded" -> code;
            default -> "generation_failed";
        };
    }
}
