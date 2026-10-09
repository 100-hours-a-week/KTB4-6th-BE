package com.backend.meety.domain.ai.client;

public record ChatAiHistoryMessage(
        String role,
        String content
) {

    public static ChatAiHistoryMessage user(String content) {
        return new ChatAiHistoryMessage(ChatAiRole.USER, content);
    }

    public static ChatAiHistoryMessage assistant(String content) {
        return new ChatAiHistoryMessage(ChatAiRole.ASSISTANT, content);
    }
}
