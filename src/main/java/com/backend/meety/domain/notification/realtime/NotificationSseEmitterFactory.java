package com.backend.meety.domain.notification.realtime;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class NotificationSseEmitterFactory {

    private static final long NO_TIMEOUT_MILLIS = 0L;

    public SseEmitter create() {
        return new SseEmitter(NO_TIMEOUT_MILLIS);
    }
}
