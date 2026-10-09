package com.backend.meety.domain.notification.realtime;

import java.time.LocalDateTime;

public record NotificationSseHeartbeatEvent(
        String type,
        LocalDateTime sentAt
) {

    private static final String TYPE = "HEARTBEAT";

    public static NotificationSseHeartbeatEvent heartbeat(LocalDateTime sentAt) {
        return new NotificationSseHeartbeatEvent(TYPE, sentAt);
    }
}
