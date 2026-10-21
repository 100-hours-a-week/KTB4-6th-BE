package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.meeting.realtime.*;
import com.backend.meety.domain.recording.event.RecordingFailedEvent;
import com.backend.meety.domain.recording.owner.*;
import com.backend.meety.global.config.RedisConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "AI_AUDIO_ACK_TEST_REDIS_PORT", matches = "[0-9]+")
class RealtimeFatalRedisIntegrationTest {
    @Test @DisplayName("Fatal 종료는 실제 Redis Owner·checkpoint를 해제하고 meeting-sse 채널에 실패 원인을 발행한다")
    void ownerCleanupAndRealPubSub() throws Exception {
        // 테스트 목적:
        // 기존 조건부 Lua 해제와 실제 Redis PubSub 계약이 Fatal에도 사용되는지 검증한다.
        // given
        var factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("AI_AUDIO_ACK_TEST_REDIS_PORT")));
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        var container = new RedisConfig().redisMessageListenerContainer(factory);
        var payload = new AtomicReference<String>(); var received = new CountDownLatch(1);
        var mapper = new ObjectMapper();
        var keys = List.of(RecordingRedisKeys.owner(317L), RecordingRedisKeys.sequence(317L));
        try {
            var owners = new RecordingOwnerRepository(redis, new RecordingOwnerProperties(Duration.ofSeconds(30), Duration.ofSeconds(10)));
            owners.save(317L, "owner"); redis.opsForValue().set(keys.get(1), "109");
            container.addMessageListener((message, pattern) -> {
                payload.set(new String(message.getBody(), StandardCharsets.UTF_8)); received.countDown();
            }, new ChannelTopic("meeting-sse"));
            container.afterPropertiesSet(); container.start();
            var audio = new AudioWebSocketRegistry();
            var fe = mock(WebSocketSession.class); when(fe.isOpen()).thenReturn(true); audio.replace(317L, fe);
            var listener = new RealtimeFatalFailureListener(mock(AiLiveMeetingConnectionService.class), audio, owners,
                    mapper, new SimpleMeterRegistry());
            var event = new RecordingFailedEvent(42L, 317L, "owner", RealtimeFailureReason.AUDIO_ACK_TIMEOUT);
            // when
            listener.afterFailed(event);
            new RecordingSseEventListener(new MeetingSsePublisher(redis, mapper)).broadcastFailed(event);
            // then
            assertThat(received.await(3, TimeUnit.SECONDS)).isTrue();
            var message = mapper.readTree(payload.get());
            assertThat(message.path("eventName").asText()).isEqualTo("RECORDING_FAILED");
            assertThat(message.path("payload").path("reason").asText()).isEqualTo("AUDIO_ACK_TIMEOUT");
            assertThat(owners.find(317L)).isEmpty(); assertThat(redis.hasKey(keys.get(1))).isFalse();
            verify(fe).close(CloseStatus.SERVER_ERROR);
        } finally { container.destroy(); redis.delete(keys); factory.destroy(); }
    }

    @Test @DisplayName("이전 Owner의 Fatal cleanup은 실제 Redis의 새 Owner와 checkpoint 및 FE 연결을 보존한다")
    void compareDeletePreservesNewOwner() throws Exception {
        // 테스트 목적:
        // 이전 BE가 늦게 cleanup해도 새 Owner의 값을 삭제하거나 새 FE를 종료하지 않는지 검증한다.
        // given
        var factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("AI_AUDIO_ACK_TEST_REDIS_PORT")));
        factory.afterPropertiesSet(); factory.start(); var redis = new StringRedisTemplate(factory);
        var keys = List.of(RecordingRedisKeys.owner(318L), RecordingRedisKeys.sequence(318L));
        try {
            var owners = new RecordingOwnerRepository(redis, new RecordingOwnerProperties(Duration.ofSeconds(30), Duration.ofSeconds(10)));
            owners.save(318L, "new-owner"); redis.opsForValue().set(keys.get(1), "200");
            var audio = new AudioWebSocketRegistry(); var fe = mock(WebSocketSession.class); audio.replace(318L, fe);
            var listener = new RealtimeFatalFailureListener(mock(AiLiveMeetingConnectionService.class), audio, owners,
                    new ObjectMapper(), new SimpleMeterRegistry());
            // when
            listener.afterFailed(new RecordingFailedEvent(42L, 318L, "old-owner", RealtimeFailureReason.AI_CONNECTION_CLOSED));
            // then
            assertThat(owners.find(318L)).hasValue("new-owner");
            assertThat(redis.opsForValue().get(keys.get(1))).isEqualTo("200");
            verifyNoInteractions(fe);
        } finally { redis.delete(keys); factory.destroy(); }
    }
}
