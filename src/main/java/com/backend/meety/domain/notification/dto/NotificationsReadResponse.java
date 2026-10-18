package com.backend.meety.domain.notification.dto;

public record NotificationsReadResponse(
        long readCount,
        long unreadCount
) {
}
