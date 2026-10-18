package com.backend.meety.domain.ai.event;

public record ChatCompletedEvent(
        Long meetingId,
        Long messageId
) {
}
