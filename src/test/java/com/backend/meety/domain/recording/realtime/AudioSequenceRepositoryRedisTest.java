package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.OptionalLong;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "RECORDING_TEST_REDIS_PORT", matches = "[0-9]+")
class AudioSequenceRepositoryRedisTest {

    private static final Long RECORDING_SESSION_ID = 990_002L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private AudioSequenceRepository sequenceRepository;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                "localhost", Integer.parseInt(System.getenv("RECORDING_TEST_REDIS_PORT")));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        sequenceRepository = new AudioSequenceRepository(
                redisTemplate, new AudioSequenceProperties(Duration.ofMinutes(90)));
        redisTemplate.delete(sequenceKey());
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(sequenceKey());
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("순번을 hash tag 키에 저장하고 저장할 때마다 TTL을 다시 건다")
    void savesSequenceWithTtl() {
        sequenceRepository.save(RECORDING_SESSION_ID, 120L);
        redisTemplate.expire(sequenceKey(), Duration.ofSeconds(5));

        sequenceRepository.save(RECORDING_SESSION_ID, 121L);

        assertThat(sequenceKey()).isEqualTo("recording:{990002}:sequence");
        assertThat(sequenceRepository.find(RECORDING_SESSION_ID)).isEqualTo(OptionalLong.of(121L));
        assertThat(redisTemplate.getExpire(sequenceKey())).isGreaterThan(5L);
    }

    @Test
    @DisplayName("저장된 순번이 없으면 빈 값을 돌려준다")
    void returnsEmptyWhenMissing() {
        assertThat(sequenceRepository.find(RECORDING_SESSION_ID)).isEmpty();
    }

    private String sequenceKey() {
        return AudioSequenceRepository.sequenceKey(RECORDING_SESSION_ID);
    }
}
