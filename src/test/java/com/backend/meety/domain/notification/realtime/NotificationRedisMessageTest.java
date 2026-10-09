package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class NotificationRedisMessageTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("NotificationCreatedEvent를 Redis 전송 메시지로 변환한다")
    void fromNotificationCreatedEvent() {
        // 테스트 목적:
        // Spring 내부 이벤트와 Redis 전송 메시지를 분리하되
        // Redis fan-out에 필요한 알림 생성 데이터를 누락 없이 옮기는지 검증한다.

        // given
        NotificationCreatedEvent event = notificationCreatedEvent();

        // when
        NotificationRedisMessage result = NotificationRedisMessage.from(event);

        // then
        assertThat(result).isEqualTo(notificationRedisMessage());
    }

    @Test
    @DisplayName("NotificationRedisMessage는 JSON으로 직렬화하고 역직렬화할 수 있다")
    void serializeAndDeserialize() {
        // 테스트 목적:
        // Redis Pub/Sub에 문자열 JSON으로 전달한 메시지가
        // Subscriber에서 동일한 데이터로 복원되는지 검증한다.

        // given
        NotificationRedisMessage message = notificationRedisMessage();

        // when
        String json = objectMapper.writeValueAsString(message);
        NotificationRedisMessage result = objectMapper.readValue(json, NotificationRedisMessage.class);

        // then
        assertThat(json).contains("\"notificationId\":15");
        assertThat(json).contains("\"recipientUserId\":1");
        assertThat(json).contains("\"notificationType\":\"MEMBER_JOINED\"");
        assertThat(json).contains("\"isRead\":false");
        assertThat(json).contains("\"createdAt\":\"2026-10-09T13:30:12\"");
        assertThat(result).isEqualTo(message);
    }

    private NotificationCreatedEvent notificationCreatedEvent() {
        return new NotificationCreatedEvent(
                15L,
                1L,
                NotificationType.MEMBER_JOINED,
                "test2 님이 팀에 합류했습니다",
                NotificationReferenceType.TEAM,
                null,
                false,
                LocalDateTime.of(2026, 10, 9, 13, 30, 12)
        );
    }

    private NotificationRedisMessage notificationRedisMessage() {
        return new NotificationRedisMessage(
                15L,
                1L,
                NotificationType.MEMBER_JOINED,
                "test2 님이 팀에 합류했습니다",
                NotificationReferenceType.TEAM,
                null,
                false,
                LocalDateTime.of(2026, 10, 9, 13, 30, 12)
        );
    }
}
