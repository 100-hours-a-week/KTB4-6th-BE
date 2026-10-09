package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

class RedisAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(DataRedisAutoConfiguration.class))
            .withPropertyValues(
                    "spring.data.redis.host=127.0.0.1",
                    "spring.data.redis.port=16379",
                    "spring.data.redis.password=test-password"
            );

    @Test
    @DisplayName("Redis 서버 연결 없이 공통 Redis Bean을 자동 구성한다")
    void configureRedisBeansWithoutConnectingToRedisServer() {

        // 테스트 목적:
        // Redis 서버가 실행되지 않은 환경에서도 연결 설정만으로 애플리케이션 컨텍스트가 생성되고
        // 문자열 기반 Redis 사용에 필요한 공통 Bean이 자동 구성되는지 검증한다.

        // given

        // when
        contextRunner.run(context -> {

            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(RedisConnectionFactory.class);
            assertThat(context).hasSingleBean(StringRedisTemplate.class);
            assertThat(context).doesNotHaveBean(RedisMessageListenerContainer.class);

            LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);
            assertThat(connectionFactory.getHostName()).isEqualTo("127.0.0.1");
            assertThat(connectionFactory.getPort()).isEqualTo(16379);
            assertThat(connectionFactory.getStandaloneConfiguration().getPassword().isPresent()).isTrue();

            StringRedisTemplate redisTemplate = context.getBean(StringRedisTemplate.class);
            assertThat(redisTemplate.getKeySerializer()).isInstanceOf(StringRedisSerializer.class);
            assertThat(redisTemplate.getValueSerializer()).isInstanceOf(StringRedisSerializer.class);
            assertThat(redisTemplate.getHashKeySerializer()).isInstanceOf(StringRedisSerializer.class);
            assertThat(redisTemplate.getHashValueSerializer()).isInstanceOf(StringRedisSerializer.class);
        });
    }

    @Test
    @DisplayName("Redis 비밀번호가 비어 있으면 비밀번호 없이 연결하도록 구성한다")
    void configureRedisWithoutPassword() {

        // 테스트 목적:
        // REDIS_PASSWORD가 설정되지 않은 환경에서는 빈 비밀번호로 인증을 시도하지 않고
        // 비밀번호 없는 Redis 연결 설정이 구성되는지 검증한다.

        // given
        ApplicationContextRunner contextRunnerWithoutPassword = contextRunner
                .withPropertyValues("spring.data.redis.password=");

        // when
        contextRunnerWithoutPassword.run(context -> {

            // then
            assertThat(context).hasNotFailed();

            LettuceConnectionFactory connectionFactory = context.getBean(LettuceConnectionFactory.class);
            assertThat(connectionFactory.getStandaloneConfiguration().getPassword().isPresent()).isFalse();
        });
    }
}
