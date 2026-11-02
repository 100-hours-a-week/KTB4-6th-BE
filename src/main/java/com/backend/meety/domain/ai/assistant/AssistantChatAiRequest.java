package com.backend.meety.domain.ai.assistant;

import com.backend.meety.domain.ai.client.ChatAiHistoryMessage;
import java.util.List;
import java.util.Objects;

public record AssistantChatAiRequest(
        Long aiRequestId,
        Long teamId,
        Long requesterTeamMemberId,
        String question,
        List<ChatAiHistoryMessage> recentMessages
) {
    public AssistantChatAiRequest {
        Objects.requireNonNull(requesterTeamMemberId, "requesterTeamMemberId is required");
    }
}
