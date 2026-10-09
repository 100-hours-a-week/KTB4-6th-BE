package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.LocalDateTime;

public record ChatCompletedSseEvent(
        String type,
        Long meetingId,
        Long messageId,
        String answer,
        @JsonRawValue String citations,
        LocalDateTime answeredAt
) {

    public static ChatCompletedSseEvent of(String type, AiChatbotMessage message) {
        return new ChatCompletedSseEvent(
                type,
                message.getMeeting().getId(),
                message.getId(),
                message.getAnswer(),
                message.getCitations(),
                message.getAnsweredAt()
        );
    }
}
