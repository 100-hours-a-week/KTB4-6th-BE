package com.backend.meety.domain.notification.realtime;

import java.time.LocalDateTime;

public record NotificationSseConnectedEvent(
        String type,
        LocalDateTime connectedAt
) {

    private static final String TYPE = "CONNECTED";

    public static NotificationSseConnectedEvent connected(LocalDateTime connectedAt) {
        return new NotificationSseConnectedEvent(TYPE, connectedAt);
    }
}
