package com.backend.meety.domain.notification.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationSseHeartbeatScheduler {

    static final long HEARTBEAT_INTERVAL_MILLIS = 30_000L;

    private final NotificationSseService notificationSseService;

    @Scheduled(fixedRate = HEARTBEAT_INTERVAL_MILLIS)
    public void sendHeartbeat() {
        notificationSseService.sendHeartbeat();
    }
}
