package com.backend.meety.domain.notification.realtime;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationRedisSubscriber implements MessageListener {

    private final ObjectMapper objectMapper;
    private final NotificationSseService notificationSseService;

    @Override
    public void onMessage(Message message, byte[] pattern) {
        String payload = new String(message.getBody(), StandardCharsets.UTF_8);
        try {
            NotificationRedisMessage redisMessage = objectMapper.readValue(payload, NotificationRedisMessage.class);
            if (redisMessage.recipientUserId() == null) {
                log.warn("알림 Redis 메시지에 수신자 userId가 없습니다. channel={}, payload={}",
                        channel(message), payload);
                return;
            }

            int sentCount = notificationSseService.sendNotificationCreated(
                    redisMessage.recipientUserId(),
                    NotificationCreatedSseEvent.from(redisMessage)
            );
            if (sentCount == 0) {
                log.debug("현재 인스턴스에 알림 SSE 연결이 없습니다. notificationId={}, recipientUserId={}",
                        redisMessage.notificationId(), redisMessage.recipientUserId());
            }
        } catch (JacksonException e) {
            log.warn("알림 Redis 메시지 역직렬화에 실패했습니다. channel={}, payload={}",
                    channel(message), payload, e);
        } catch (RuntimeException e) {
            log.warn("알림 Redis 메시지 처리에 실패했습니다. channel={}, payload={}",
                    channel(message), payload, e);
        }
    }

    private String channel(Message message) {
        return new String(message.getChannel(), StandardCharsets.UTF_8);
    }
}
