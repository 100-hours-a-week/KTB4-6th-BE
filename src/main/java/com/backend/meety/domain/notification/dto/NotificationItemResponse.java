package com.backend.meety.domain.notification.dto;

import com.backend.meety.domain.notification.entity.Notification;
import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import java.time.LocalDateTime;

public record NotificationItemResponse(
        Long notificationId,
        NotificationType type,
        String body,
        NotificationReferenceType referenceType,
        Long referenceId,
        boolean isRead,
        LocalDateTime createdAt
) {

    public static NotificationItemResponse from(Notification notification) {
        return new NotificationItemResponse(
                notification.getId(),
                notification.getType(),
                notification.getBody(),
                notification.getReferenceType(),
                notification.getReferenceId(),
                notification.isRead(),
                notification.getCreatedAt()
        );
    }
}
