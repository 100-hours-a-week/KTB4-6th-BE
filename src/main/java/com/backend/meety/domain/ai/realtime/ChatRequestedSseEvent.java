package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.ChatInputType;
import java.time.LocalDateTime;

public record ChatRequestedSseEvent(
        String type,
        Long meetingId,
        Long messageId,
        Long askerTeamMemberId,
        String askerDisplayName,
        ChatInputType inputType,
        String question,
        LocalDateTime createdAt,
        Long creditBalance
) {

    public static ChatRequestedSseEvent of(String type, AiChatbotMessage message, Long creditBalance) {
        return new ChatRequestedSseEvent(
                type,
                message.getMeeting().getId(),
                message.getId(),
                message.getTeamMember().getId(),
                message.getTeamMember().getDisplayName(),
                message.getInputType(),
                message.getQuestion(),
                message.getCreatedAt(),
                creditBalance
        );
    }
}
