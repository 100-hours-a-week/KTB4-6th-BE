package com.backend.meety.domain.ai.assistant;

import java.util.function.Consumer;

public interface AssistantChatAiClient {
    AssistantChatAiResult stream(AssistantChatAiRequest request, Consumer<AssistantChatAiEvent> events);
}
