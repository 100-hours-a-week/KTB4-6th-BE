package com.backend.meety.domain.notification.realtime;

import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRedisPublisher {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(NotificationCreatedEvent event) {
        try {
            String message = objectMapper.writeValueAsString(NotificationRedisMessage.from(event));
            stringRedisTemplate.convertAndSend(NotificationRedisChannels.NOTIFICATION_SSE, message);
        } catch (RuntimeException e) {
            log.warn("알림 Redis publish에 실패했습니다. notificationId={}, recipientUserId={}",
                    event.notificationId(), event.recipientUserId(), e);
        }
    }
}
