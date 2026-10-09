package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class NotificationRedisSubscriptionConfigTest {

    @Test
    @DisplayName("알림 SSE Redis channel은 notification-sse를 사용한다")
    void notificationSseTopicUsesNotificationChannel() {
        // 테스트 목적:
        // 알림 실시간 전파가 meeting-sse와 분리된
        // notification 전용 Redis channel을 사용하는지 검증한다.

        // given
        NotificationRedisSubscriptionConfig config = new NotificationRedisSubscriptionConfig();

        // when
        ChannelTopic topic = config.notificationSseTopic();

        // then
        assertThat(topic.getTopic()).isEqualTo("notification-sse");
    }

    @Test
    @DisplayName("기존 RedisMessageListenerContainer에 알림 Subscriber를 등록한다")
    void registerNotificationSubscriberToExistingContainer() {
        // 테스트 목적:
        // Redis 공통 연결 설정을 새로 만들지 않고
        // 기존 RedisMessageListenerContainer에 알림 Subscriber와 channel을 추가하는지 검증한다.

        // given
        NotificationRedisSubscriptionConfig config = new NotificationRedisSubscriptionConfig();
        RedisMessageListenerContainer container = mock(RedisMessageListenerContainer.class);
        NotificationRedisSubscriber subscriber = mock(NotificationRedisSubscriber.class);
        ChannelTopic topic = config.notificationSseTopic();

        // when
        SmartInitializingSingleton initializer =
                config.notificationRedisSubscription(container, subscriber, topic);
        initializer.afterSingletonsInstantiated();

        // then
        verify(container).addMessageListener(subscriber, topic);
    }
}
