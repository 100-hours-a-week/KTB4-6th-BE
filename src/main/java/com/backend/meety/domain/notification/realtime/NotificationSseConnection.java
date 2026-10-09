package com.backend.meety.domain.notification.realtime;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

record NotificationSseConnection(
        Long userId,
        String connectionId,
        SseEmitter emitter
) {
}
