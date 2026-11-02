package com.backend.meety.domain.ai.assistant;

import com.backend.meety.domain.ai.client.ChatAiHistoryMessage;
import java.util.List;

public record AssistantChatWork(Long requestId, String processingToken, Long teamId, Long requesterTeamMemberId,
                                String question,
                                List<ChatAiHistoryMessage> recentMessages) {
}
