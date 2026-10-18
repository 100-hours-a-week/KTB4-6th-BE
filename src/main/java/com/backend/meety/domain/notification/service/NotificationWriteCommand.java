package com.backend.meety.domain.notification.service;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;

public record NotificationWriteCommand(
        Long teamId,
        Long userId,
        NotificationType type,
        String idempotencyKey,
        NotificationReferenceType referenceType,
        Long referenceId,
        String body
) {
}
