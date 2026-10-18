package com.backend.meety.domain.notification.service;

public record NotificationRecipient(
        Long teamId,
        Long userId
) {

    public boolean isUser(Long targetUserId) {
        return userId.equals(targetUserId);
    }
}
