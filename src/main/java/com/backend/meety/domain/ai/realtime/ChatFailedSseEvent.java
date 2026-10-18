package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.event.ChatFailedEvent;

public record ChatFailedSseEvent(
        String type,
        Long meetingId,
        Long messageId,
        Long creditBalance
) {

    public static ChatFailedSseEvent of(String type, ChatFailedEvent event) {
        return new ChatFailedSseEvent(type, event.meetingId(), event.messageId(), event.creditBalance());
    }
}
