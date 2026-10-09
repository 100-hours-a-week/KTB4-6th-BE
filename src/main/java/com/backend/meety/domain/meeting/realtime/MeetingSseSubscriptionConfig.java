package com.backend.meety.domain.meeting.realtime;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class MeetingSseSubscriptionConfig {

    @Bean(name = "meetingSseTopic")
    public ChannelTopic meetingSseTopic() {
        return new ChannelTopic(MeetingSseChannels.MEETING_SSE);
    }

    @Bean
    public SmartInitializingSingleton meetingSseSubscription(
            RedisMessageListenerContainer redisMessageListenerContainer,
            MeetingSseSubscriber meetingSseSubscriber,
            @Qualifier("meetingSseTopic") ChannelTopic meetingSseTopic
    ) {
        return () -> redisMessageListenerContainer.addMessageListener(meetingSseSubscriber, meetingSseTopic);
    }
}
