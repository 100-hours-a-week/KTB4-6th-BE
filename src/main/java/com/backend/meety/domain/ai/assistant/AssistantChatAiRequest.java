package com.backend.meety.domain.ai.assistant;

import com.backend.meety.domain.ai.client.ChatAiHistoryMessage;
import java.util.List;

public record AssistantChatAiRequest(
        Long aiRequestId,
        Long teamId,
        String question,
        List<ChatAiHistoryMessage> recentMessages
) {
}
