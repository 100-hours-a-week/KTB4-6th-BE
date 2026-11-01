package com.backend.meety.domain.ai.assistant;

import tools.jackson.databind.JsonNode;

public record AssistantChatAiEvent(String name, JsonNode data) {
}
