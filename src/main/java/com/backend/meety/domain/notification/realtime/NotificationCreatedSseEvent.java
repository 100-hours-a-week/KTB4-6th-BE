package com.backend.meety.domain.notification.realtime;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import java.time.LocalDateTime;

public record NotificationCreatedSseEvent(
        String type,
        Long notificationId,
        NotificationType notificationType,
        String body,
        NotificationReferenceType referenceType,
        Long referenceId,
        boolean isRead,
        LocalDateTime createdAt
) {

    private static final String TYPE = "NOTIFICATION_CREATED";

    public static NotificationCreatedSseEvent from(NotificationCreatedEvent event) {
        return new NotificationCreatedSseEvent(
                TYPE,
                event.notificationId(),
                event.notificationType(),
                event.body(),
                event.referenceType(),
                event.referenceId(),
                event.isRead(),
                event.createdAt()
        );
    }

    public static NotificationCreatedSseEvent from(NotificationRedisMessage message) {
        return new NotificationCreatedSseEvent(
                TYPE,
                message.notificationId(),
                message.notificationType(),
                message.body(),
                message.referenceType(),
                message.referenceId(),
                message.isRead(),
                message.createdAt()
        );
    }
}
