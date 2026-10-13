package com.backend.meety.domain.recording.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

@EnabledIfEnvironmentVariable(named = "RECORDING_TEST_REDIS_PORT", matches = "[0-9]+")
class RecordingOwnerRepositoryRedisTest {

    private static final Long RECORDING_SESSION_ID = 990_001L;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private RecordingOwnerRepository ownerRepository;

    @BeforeEach
    void setUp() {
        connectionFactory = new LettuceConnectionFactory(
                "localhost", Integer.parseInt(System.getenv("RECORDING_TEST_REDIS_PORT")));
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();
        redisTemplate = new StringRedisTemplate(connectionFactory);
        ownerRepository = new RecordingOwnerRepository(
                redisTemplate, new RecordingOwnerProperties(Duration.ofSeconds(30)));
        redisTemplate.delete(ownerKey());
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(ownerKey());
        connectionFactory.destroy();
    }

    @Test
    @DisplayName("Owner를 hash tag 키에 기록하고 TTL을 건다")
    void savesOwnerWithTtl() {
        ownerRepository.save(RECORDING_SESSION_ID, "server-a:1");

        assertThat(ownerKey()).isEqualTo("recording:{990001}:owner");
        assertThat(redisTemplate.opsForValue().get(ownerKey())).isEqualTo("server-a:1");
        assertThat(redisTemplate.getExpire(ownerKey())).isBetween(1L, 30L);
    }

    @Test
    @DisplayName("기존 Owner가 있어도 확인 없이 덮어쓰고 TTL을 다시 건다")
    void overwritesExistingOwner() {
        ownerRepository.save(RECORDING_SESSION_ID, "server-a:1");
        redisTemplate.expire(ownerKey(), Duration.ofSeconds(5));

        ownerRepository.save(RECORDING_SESSION_ID, "server-b:7");

        assertThat(redisTemplate.opsForValue().get(ownerKey())).isEqualTo("server-b:7");
        assertThat(redisTemplate.getExpire(ownerKey())).isGreaterThan(5L);
    }

    private String ownerKey() {
        return RecordingOwnerRepository.ownerKey(RECORDING_SESSION_ID);
    }
}
