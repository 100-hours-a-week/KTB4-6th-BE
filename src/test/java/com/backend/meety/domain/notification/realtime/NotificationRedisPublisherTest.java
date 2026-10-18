package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

class NotificationRedisPublisherTest {

    @Test
    @DisplayName("알림 생성 이벤트를 notification 전용 Redis channel에 publish한다")
    void publishSendsMessageToNotificationChannel() {
        // 테스트 목적:
        // NotificationCreatedEvent가 Redis 전송 메시지 JSON으로 변환되어
        // meeting-sse가 아닌 notification 전용 channel로 publish되는지 검증한다.

        // given
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        NotificationRedisPublisher publisher = new NotificationRedisPublisher(stringRedisTemplate, objectMapper);
        NotificationCreatedEvent event = notificationCreatedEvent();

        // when
        publisher.publish(event);

        // then
        ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
        verify(stringRedisTemplate).convertAndSend(
                eq(NotificationRedisChannels.NOTIFICATION_SSE),
                messageCaptor.capture()
        );
        NotificationRedisMessage message =
                objectMapper.readValue(messageCaptor.getValue(), NotificationRedisMessage.class);
        assertThat(message).isEqualTo(NotificationRedisMessage.from(event));
    }

    @Test
    @DisplayName("Redis publish 실패는 Publisher 밖으로 전파하지 않는다")
    void redisPublishFailureDoesNotPropagate() {
        // 테스트 목적:
        // Redis 장애로 convertAndSend가 실패해도 이미 저장된 Notification과
        // 원래 비즈니스 요청을 실패시키지 않도록 예외를 삼키는지 검증한다.

        // given
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = new ObjectMapper();
        NotificationRedisPublisher publisher = new NotificationRedisPublisher(stringRedisTemplate, objectMapper);
        NotificationCreatedEvent event = notificationCreatedEvent();
        when(stringRedisTemplate.convertAndSend(eq(NotificationRedisChannels.NOTIFICATION_SSE), anyString()))
                .thenThrow(new IllegalStateException("redis down"));

        // when, then
        assertThatCode(() -> publisher.publish(event))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("Redis 메시지 직렬화 실패는 publish를 시도하지 않고 종료한다")
    void serializeFailureDoesNotPublish() {
        // 테스트 목적:
        // Redis 메시지 JSON 직렬화가 실패하면 잘못된 메시지를 publish하지 않고
        // 예외를 Publisher 밖으로 전파하지 않는지 검증한다.

        // given
        StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);
        ObjectMapper objectMapper = mock(ObjectMapper.class);
        NotificationRedisPublisher publisher = new NotificationRedisPublisher(stringRedisTemplate, objectMapper);
        NotificationCreatedEvent event = notificationCreatedEvent();
        when(objectMapper.writeValueAsString(any()))
                .thenThrow(new TestJacksonException("json failed"));

        // when, then
        assertThatCode(() -> publisher.publish(event))
                .doesNotThrowAnyException();
        verify(stringRedisTemplate, never()).convertAndSend(anyString(), anyString());
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

    private static class TestJacksonException extends JacksonException {

        TestJacksonException(String message) {
            super(message);
        }
    }
}
