package com.backend.meety.domain.ai.event;

public record ChatRequestedEvent(
        Long aiRequestId,
        Long meetingId,
        Long messageId,
        Long creditBalance
) {
}
