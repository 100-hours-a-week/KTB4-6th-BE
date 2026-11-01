package com.backend.meety.domain.ai.assistant.dto;

import java.util.List;

public record AssistantChatPageResponse(List<AssistantChatTurnResponse> messages, Long nextCursor,
                                        boolean hasNext) {
}
