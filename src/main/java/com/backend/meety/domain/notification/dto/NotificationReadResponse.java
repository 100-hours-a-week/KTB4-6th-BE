package com.backend.meety.domain.notification.dto;

public record NotificationReadResponse(
        Long notificationId,
        boolean isRead,
        long unreadCount
) {
}
