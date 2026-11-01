package com.backend.meety.domain.ai.assistant;

public record AssistantChatSubmission(Long requestId, Long conversationId, boolean created) {
}
