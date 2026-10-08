package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

class RedisConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
            .withUserConfiguration(RedisConfig.class)
            .withPropertyValues(
                    "spring.data.redis.host=127.0.0.1",
                    "spring.data.redis.port=16379"
            );

    @Test
    @DisplayName("Redis 서버 없이도 회의 SSE 채널과 구독 컨테이너를 구성하고 기동한다")
    void configureMeetingSsePubSubWithoutRedisServer() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(ChannelTopic.class).getTopic()).isEqualTo("meeting-sse");
            assertThat(context.getBean(RedisMessageListenerContainer.class).isRunning()).isTrue();
        });
    }
}
