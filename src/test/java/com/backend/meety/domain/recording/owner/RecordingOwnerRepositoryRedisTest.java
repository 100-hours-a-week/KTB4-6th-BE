package com.backend.meety.domain.recording.owner;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
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
                redisTemplate, new RecordingOwnerProperties(Duration.ofSeconds(30), Duration.ofSeconds(10)));
        redisTemplate.delete(List.of(ownerKey(), sequenceKey()));
    }

    @AfterEach
    void tearDown() {
        redisTemplate.delete(List.of(ownerKey(), sequenceKey()));
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

    @Test
    @DisplayName("Redis Owner가 자신의 값일 때만 TTL을 연장한다")
    void refreshesOnlyWhenOwnerMatches() {
        ownerRepository.save(RECORDING_SESSION_ID, "server-a:1");
        redisTemplate.expire(ownerKey(), Duration.ofSeconds(5));

        assertThat(ownerRepository.refreshIfOwner(RECORDING_SESSION_ID, "server-a:0")).isFalse();
        assertThat(redisTemplate.getExpire(ownerKey())).isLessThanOrEqualTo(5L);

        assertThat(ownerRepository.refreshIfOwner(RECORDING_SESSION_ID, "server-a:1")).isTrue();
        assertThat(redisTemplate.getExpire(ownerKey())).isGreaterThan(5L);
    }

    @Test
    @DisplayName("Owner가 없으면 갱신하지 않고 키를 다시 만들지 않는다")
    void doesNotRecreateMissingOwner() {
        assertThat(ownerRepository.refreshIfOwner(RECORDING_SESSION_ID, "server-a:1")).isFalse();
        assertThat(redisTemplate.hasKey(ownerKey())).isFalse();
    }

    @Test
    @DisplayName("Redis Owner가 자신의 값일 때만 Owner와 sequence를 함께 삭제한다")
    void deletesOwnerAndSequenceOnlyWhenOwnerMatches() {
        ownerRepository.save(RECORDING_SESSION_ID, "server-b:7");
        redisTemplate.opsForValue().set(sequenceKey(), "150");

        assertThat(ownerRepository.deleteIfOwner(RECORDING_SESSION_ID, "server-a:1")).isFalse();
        assertThat(redisTemplate.hasKey(ownerKey())).isTrue();
        assertThat(redisTemplate.hasKey(sequenceKey())).isTrue();

        assertThat(ownerRepository.deleteIfOwner(RECORDING_SESSION_ID, "server-b:7")).isTrue();
        assertThat(redisTemplate.hasKey(ownerKey())).isFalse();
        assertThat(redisTemplate.hasKey(sequenceKey())).isFalse();
    }

    private String sequenceKey() {
        return RecordingRedisKeys.sequence(RECORDING_SESSION_ID);
    }

    private String ownerKey() {
        return RecordingRedisKeys.owner(RECORDING_SESSION_ID);
    }
}
