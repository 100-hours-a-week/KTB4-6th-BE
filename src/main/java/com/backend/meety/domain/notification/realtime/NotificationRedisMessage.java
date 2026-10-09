package com.backend.meety.domain.notification.realtime;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import java.time.LocalDateTime;

public record NotificationRedisMessage(
        Long notificationId,
        Long recipientUserId,
        NotificationType notificationType,
        String body,
        NotificationReferenceType referenceType,
        Long referenceId,
        boolean isRead,
        LocalDateTime createdAt
) {

    public static NotificationRedisMessage from(NotificationCreatedEvent event) {
        return new NotificationRedisMessage(
                event.notificationId(),
                event.recipientUserId(),
                event.notificationType(),
                event.body(),
                event.referenceType(),
                event.referenceId(),
                event.isRead(),
                event.createdAt()
        );
    }
}
