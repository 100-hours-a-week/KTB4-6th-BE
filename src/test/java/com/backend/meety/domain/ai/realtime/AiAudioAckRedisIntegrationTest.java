package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.backend.meety.domain.recording.owner.RecordingRedisKeys;
import com.backend.meety.domain.recording.realtime.AudioSequenceProperties;
import com.backend.meety.domain.recording.realtime.AudioSequenceRepository;
import com.backend.meety.domain.recording.realtime.AudioWebSocketRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "AI_AUDIO_ACK_TEST_REDIS_PORT", matches = "[0-9]+")
class AiAudioAckRedisIntegrationTest {
    private static final long RECORDING_ID = 3_190_001L;
    private LettuceConnectionFactory factory;
    private StringRedisTemplate redis;
    private AudioSequenceRepository repository;

    @BeforeEach
    void setUp() {
        factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("AI_AUDIO_ACK_TEST_REDIS_PORT")));
        factory.afterPropertiesSet();
        factory.start();
        redis = new StringRedisTemplate(factory);
        repository = new AudioSequenceRepository(redis, new AudioSequenceProperties(Duration.ofMinutes(5)));
        redis.delete(List.of(sequenceKey(), ownerKey()));
    }

    @AfterEach
    void tearDown() {
        redis.delete(List.of(sequenceKey(), ownerKey()));
        factory.destroy();
    }

    @Test
    @DisplayName("실제 Redis checkpoint는 AI ACK 이후에 기록되고 FE ACK보다 먼저 확인된다")
    void endToEndCheckpointPrecedesFeAck() throws Exception {
        // 테스트 목적:
        // 실제 Redis 쓰기와 FE 응답의 순서를 검증하고 소켓 write 성공을 수신 확인으로 오인하지 않는지 확인한다.
        // given
        var connections = new AiLiveMeetingConnectionRegistry();
        var audioRegistry = new AudioWebSocketRegistry();
        var connection = new AiLiveMeetingConnection(RECORDING_ID, 42L, AudioFormat.WEBM_OPUS, "redis-connection");
        var ai = mock(WebSocketSession.class);
        var fe = mock(WebSocketSession.class);
        when(ai.isOpen()).thenReturn(true);
        connection.attach(ai);
        connection.markReady();
        connection.assignOwner("server-a:1");
        redis.opsForValue().set(ownerKey(), "server-a:1");
        connections.reserve(connection);
        audioRegistry.replace(RECORDING_ID, fe);
        var service = new AiAudioAckService(connections, audioRegistry, repository,
                new AiAudioAckProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), 1024),
                Clock.systemUTC(), new SimpleMeterRegistry(), event -> { });
        ObjectMapper mapper = new ObjectMapper();
        doAnswer(call -> {
            assertThat(redis.opsForValue().get(sequenceKey())).isEqualTo("10");
            assertThat(((TextMessage) call.getArgument(0)).getPayload()).isEqualTo("{\"type\":\"ack\",\"seq\":10}");
            return null;
        }).when(fe).sendMessage(any(TextMessage.class));
        // when
        for (long sequence = 1; sequence <= 10; sequence++) {
            assertThat(service.forwardAudio(connection, mapper, 0, sequence, new byte[]{1})).isTrue();
        }
        // then
        assertThat(repository.find(RECORDING_ID)).isEmpty();
        verifyNoInteractions(fe);
        // when
        service.receive(connection, mapper.readTree("{\"recordingSessionId\":\"3190001\",\"payload\":{\"sequence\":9}}"), mapper);
        // then
        verify(fe).sendMessage(any(TextMessage.class));
        assertThat(repository.find(RECORDING_ID)).hasValue(10);
        assertThat(redis.getExpire(sequenceKey())).isBetween(1L, 300L);
    }

    @Test
    @DisplayName("Redis는 동시·역순 쓰기에서도 기존 checkpoint보다 작아지지 않는다")
    void concurrentWritesRemainMonotonic() throws Exception {
        // 테스트 목적:
        // 여러 BE에 해당하는 writer가 경쟁해도 Redis checkpoint를 원자적으로 최대값으로 유지하는지 검증한다.
        // given
        try (var pool = Executors.newFixedThreadPool(8)) {
            List<Future<?>> writes = new ArrayList<>();
            // when
            for (long i = 100; i >= 1; i--) {
                long sequence = i;
                writes.add(pool.submit(() -> repository.saveIfOwner(RECORDING_ID, null, sequence)));
            }
            for (var write : writes) { write.get(5, TimeUnit.SECONDS); }
        }
        // then
        assertThat(repository.find(RECORDING_ID)).hasValue(100);
        assertThat(repository.saveIfOwner(RECORDING_ID, null, 99)).isFalse();
        assertThat(repository.find(RECORDING_ID)).hasValue(100);
    }

    @Test
    @DisplayName("Lua checkpoint 비교는 큰 BIGINT 순번에서도 부동소수점 정밀도를 잃지 않는다")
    void largeSequencesRemainExact() {
        // 테스트 목적:
        // Redis Lua 숫자 변환으로 큰 순번이 동일하게 취급되어 역행하는 문제를 방지하는지 검증한다.
        // given
        repository.saveIfOwner(RECORDING_ID, null, Long.MAX_VALUE);
        // when
        boolean changed = repository.saveIfOwner(RECORDING_ID, null, Long.MAX_VALUE - 1);
        // then
        assertThat(changed).isFalse();
        assertThat(repository.find(RECORDING_ID)).hasValue(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("이전 Owner의 ACK는 새 Owner의 checkpoint를 생성하거나 변경하지 못한다")
    void previousOwnerCannotWrite() {
        // 테스트 목적:
        // 로컬 연결이 남아 있어도 소유권이 변경된 녹음에는 옛 응답이 반영되지 않는지 검증한다.
        // given
        redis.opsForValue().set(ownerKey(), "server-b:2");
        // when
        boolean rejected = repository.saveIfOwner(RECORDING_ID, "server-a:1", 150);
        boolean accepted = repository.saveIfOwner(RECORDING_ID, "server-b:2", 100);
        // then
        assertThat(rejected).isFalse();
        assertThat(accepted).isTrue();
        assertThat(repository.find(RECORDING_ID)).hasValue(100);
    }

    private String sequenceKey() { return RecordingRedisKeys.sequence(RECORDING_ID); }
    private String ownerKey() { return RecordingRedisKeys.owner(RECORDING_ID); }
}
