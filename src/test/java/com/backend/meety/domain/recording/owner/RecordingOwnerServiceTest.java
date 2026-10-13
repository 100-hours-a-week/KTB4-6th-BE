package com.backend.meety.domain.recording.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry;
import com.backend.meety.domain.ai.realtime.AudioFormat;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class RecordingOwnerServiceTest {

    private final RecordingOwnerRepository ownerRepository = mock(RecordingOwnerRepository.class);
    private final AiLiveMeetingConnectionRegistry aiConnectionRegistry = new AiLiveMeetingConnectionRegistry();
    private final RecordingOwnerService ownerService = new RecordingOwnerService(
            new ServerInstance("server-a"), ownerRepository, aiConnectionRegistry);

    @Test
    @DisplayName("Owner를 Redis에 기록하고 같은 값을 AI 연결에 보관한다")
    void registersOwnerAndAssignsItToAiConnection() {
        AiLiveMeetingConnection connection = connection();
        aiConnectionRegistry.reserve(connection);

        boolean registered = ownerService.register(88L);

        assertThat(registered).isTrue();
        verify(ownerRepository).save(88L, "server-a:1");
        assertThat(connection.owner()).isEqualTo("server-a:1");
    }

    @Test
    @DisplayName("AI 연결을 재사용해 다시 핸드셰이크하면 새 연결 ID로 교체한다")
    void reassignsNewConnectionIdOnReconnect() {
        AiLiveMeetingConnection connection = connection();
        aiConnectionRegistry.reserve(connection);

        ownerService.register(88L);
        ownerService.register(88L);

        verify(ownerRepository).save(88L, "server-a:2");
        assertThat(connection.owner()).isEqualTo("server-a:2");
    }

    @Test
    @DisplayName("Redis 쓰기에 실패하면 false를 반환하고 AI 연결의 Owner 값을 바꾸지 않는다")
    void returnsFalseWhenRedisFails() {
        AiLiveMeetingConnection connection = connection();
        connection.assignOwner("server-a:0");
        aiConnectionRegistry.reserve(connection);
        doThrow(new RedisConnectionFailureException("down")).when(ownerRepository).save(88L, "server-a:1");

        boolean registered = ownerService.register(88L);

        assertThat(registered).isFalse();
        assertThat(connection.owner()).isEqualTo("server-a:0");
    }

    private AiLiveMeetingConnection connection() {
        return new AiLiveMeetingConnection(88L, 42L, AudioFormat.WEBM_OPUS, "start-1");
    }
}
