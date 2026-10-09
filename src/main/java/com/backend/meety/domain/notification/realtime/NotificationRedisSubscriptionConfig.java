package com.backend.meety.domain.notification.realtime;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class NotificationRedisSubscriptionConfig {

    @Bean(name = "notificationSseTopic")
    public ChannelTopic notificationSseTopic() {
        return new ChannelTopic(NotificationRedisChannels.NOTIFICATION_SSE);
    }

    @Bean
    public SmartInitializingSingleton notificationRedisSubscription(
            RedisMessageListenerContainer redisMessageListenerContainer,
            NotificationRedisSubscriber notificationRedisSubscriber,
            @Qualifier("notificationSseTopic") ChannelTopic notificationSseTopic
    ) {
        return () -> redisMessageListenerContainer.addMessageListener(
                notificationRedisSubscriber,
                notificationSseTopic
        );
    }
}
