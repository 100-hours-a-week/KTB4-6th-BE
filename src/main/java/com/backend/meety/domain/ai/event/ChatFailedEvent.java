package com.backend.meety.domain.ai.event;

public record ChatFailedEvent(
        Long meetingId,
        Long messageId,
        Long creditBalance
) {
}
