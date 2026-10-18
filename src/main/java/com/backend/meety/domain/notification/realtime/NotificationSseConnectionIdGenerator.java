package com.backend.meety.domain.notification.realtime;

import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class NotificationSseConnectionIdGenerator {

    public String generate() {
        return UUID.randomUUID().toString();
    }
}
