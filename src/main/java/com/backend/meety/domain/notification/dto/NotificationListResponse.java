package com.backend.meety.domain.notification.dto;

import java.util.List;

public record NotificationListResponse(
        List<NotificationItemResponse> notifications,
        long unreadCount,
        Long nextCursor,
        boolean hasNext
) {
}
