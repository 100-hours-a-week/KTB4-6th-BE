package com.backend.meety.domain.ai.dto;

import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.ChatInputType;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.LocalDateTime;

public record ChatMessageResponse(
        Long messageId,
        Long askerTeamMemberId,
        String askerDisplayName,
        ChatInputType inputType,
        String question,
        String answer,
        AiRequestStatus status,
        @JsonRawValue String citations,
        LocalDateTime createdAt,
        LocalDateTime answeredAt
) {

    public static ChatMessageResponse from(AiChatbotMessage message) {
        return new ChatMessageResponse(
                message.getId(),
                message.getTeamMember().getId(),
                message.getTeamMember().getDisplayName(),
                message.getInputType(),
                message.getQuestion(),
                message.getAnswer(),
                message.getAiRequest().getStatus(),
                message.getCitations(),
                message.getCreatedAt(),
                message.getAnsweredAt()
        );
    }
}
