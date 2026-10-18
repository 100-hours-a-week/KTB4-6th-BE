package com.backend.meety.domain.notification.event;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import java.time.LocalDateTime;

public record NotificationCreatedEvent(
        Long notificationId,
        Long recipientUserId,
        NotificationType notificationType,
        String body,
        NotificationReferenceType referenceType,
        Long referenceId,
        boolean isRead,
        LocalDateTime createdAt
) {
}
