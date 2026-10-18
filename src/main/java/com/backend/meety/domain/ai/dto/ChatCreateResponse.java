package com.backend.meety.domain.ai.dto;

import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiRequestStatus;

public record ChatCreateResponse(
        Long messageId,
        AiRequestStatus status,
        Long creditBalance
) {

    public static ChatCreateResponse of(AiChatbotMessage message, long creditBalance) {
        return new ChatCreateResponse(message.getId(), message.getAiRequest().getStatus(), creditBalance);
    }
}
