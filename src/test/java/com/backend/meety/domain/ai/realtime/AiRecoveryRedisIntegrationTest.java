package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.ai.event.AiRecoverableFailureEvent;
import com.backend.meety.domain.recording.owner.RecordingRedisKeys;
import com.backend.meety.domain.recording.realtime.AudioSequenceProperties;
import com.backend.meety.domain.recording.realtime.AudioSequenceRepository;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.socket.TextMessage;

@EnabledIfEnvironmentVariable(named = "AI_AUDIO_ACK_TEST_REDIS_PORT", matches = "[0-9]+")
class AiRecoveryRedisIntegrationTest {
    @Test
    @DisplayName("실제 Redis의 부분 ACK checkpoint로 두 번째 attempt를 시작하고 새 ACK 이후 완료한다")
    void partialCheckpointSurvivesAttemptReplacement() throws Exception {
        // 테스트 목적:
        // 메모리 connection을 교체해도 안전 위치가 실제 Redis에 남고 이전 callback이 이를 덮어쓰지 않는지 검증한다.
        // given
        var factory = new LettuceConnectionFactory("127.0.0.1", Integer.parseInt(System.getenv("AI_AUDIO_ACK_TEST_REDIS_PORT")));
        factory.afterPropertiesSet(); factory.start();
        var redis = new StringRedisTemplate(factory);
        var keys = List.of(RecordingRedisKeys.owner(88L), RecordingRedisKeys.sequence(88L));
        try {
            redis.delete(keys);
            redis.opsForValue().set(keys.getFirst(), "owner-a");
            var repository = new AudioSequenceRepository(redis, new AudioSequenceProperties(Duration.ofMinutes(5)));
            assertThat(repository.saveIfOwner(88L, "owner-a", 99)).isTrue();
            var fixture = new AiRecoveryServiceTest();
            fixture.checkpointBackend.set(repository);
            fixture.begin(); fixture.runAll();
            fixture.sendAudio(100, 104);
            assertThat(repository.find(88L)).hasValue(99);
            // when
            fixture.aiAck(104); fixture.runAll();
            assertThat(repository.find(88L)).hasValue(104);
            var first = fixture.connections.find(88L).orElseThrow();
            var oldHandler = fixture.inbound.getFirst();
            var oldSocket = fixture.aiSockets.getFirst();
            fixture.recovery.onFailure(new AiRecoverableFailureEvent(88L, first.sessionStartRequestId(), "AI_TRANSPORT_ERROR"));
            fixture.runAll();
            oldHandler.handleTextMessage(oldSocket, new TextMessage(
                    "{\"type\":\"audio.ack\",\"recordingSessionId\":88,\"payload\":{\"sequence\":999}}"));
            // then
            assertThat(repository.find(88L)).hasValue(104);
            assertThat(fixture.mapper.readTree(fixture.starts.getLast()).path("startSequence").asLong()).isEqualTo(105);
            // when
            fixture.sendAudio(105, 114); fixture.aiAck(114); fixture.runAll();
            // then
            assertThat(repository.find(88L)).hasValue(114);
            assertThat(fixture.contexts.count()).isZero();
            assertThat(fixture.feMessages).anyMatch(text -> text.contains("recording.recovery.completed"));
        } finally {
            redis.delete(keys); factory.destroy();
        }
    }
}
