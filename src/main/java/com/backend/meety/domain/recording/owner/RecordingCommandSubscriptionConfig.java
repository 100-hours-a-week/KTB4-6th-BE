package com.backend.meety.domain.recording.owner;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
public class RecordingCommandSubscriptionConfig {

    @Bean
    public SmartInitializingSingleton recordingCommandSubscription(
            RedisMessageListenerContainer redisMessageListenerContainer,
            RecordingCommandSubscriber recordingCommandSubscriber,
            ServerInstance serverInstance
    ) {
        return () -> redisMessageListenerContainer.addMessageListener(
                recordingCommandSubscriber, new ChannelTopic(RecordingCommandChannels.of(serverInstance.serverId())));
    }
}
