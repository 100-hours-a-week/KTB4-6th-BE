package com.backend.meety.domain.recording.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class RecordingOwnerServiceTest {

    private final RecordingOwnerRepository ownerRepository = mock(RecordingOwnerRepository.class);
    private final AiLiveMeetingConnectionRegistry aiConnectionRegistry = new AiLiveMeetingConnectionRegistry();
    private final RecordingCommandPublisher commandPublisher = mock(RecordingCommandPublisher.class);
    private final RecordingOwnerService ownerService = new RecordingOwnerService(
            new ServerInstance("server-a"), ownerRepository, aiConnectionRegistry, commandPublisher);

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

    @Test
    @DisplayName("heartbeat는 Owner를 보관한 열린 AI 연결만 갱신한다")
    void refreshesOnlyOpenConnectionsWithOwner() {
        AiLiveMeetingConnection owned = connection(88L);
        owned.assignOwner("server-a:1");
        AiLiveMeetingConnection withoutOwner = connection(89L);
        AiLiveMeetingConnection closed = connection(90L);
        closed.assignOwner("server-a:2");
        closed.close();
        aiConnectionRegistry.reserve(owned);
        aiConnectionRegistry.reserve(withoutOwner);
        aiConnectionRegistry.reserve(closed);

        ownerService.refreshOwners();

        verify(ownerRepository).refreshIfOwner(88L, "server-a:1");
        verifyNoMoreInteractions(ownerRepository);
    }

    @Test
    @DisplayName("한 녹음의 갱신이 실패해도 나머지 녹음은 계속 갱신한다")
    void continuesRefreshWhenOneFails() {
        AiLiveMeetingConnection first = connection(88L);
        first.assignOwner("server-a:1");
        AiLiveMeetingConnection second = connection(89L);
        second.assignOwner("server-a:2");
        aiConnectionRegistry.reserve(first);
        aiConnectionRegistry.reserve(second);
        doThrow(new RedisConnectionFailureException("down")).when(ownerRepository).refreshIfOwner(88L, "server-a:1");

        ownerService.refreshOwners();

        verify(ownerRepository).refreshIfOwner(89L, "server-a:2");
    }

    @Test
    @DisplayName("Owner 해제는 자신의 Owner 값으로 조건부 삭제를 요청하고 Redis 오류는 전파하지 않는다")
    void releasesWithOwnValue() {
        doThrow(new RedisConnectionFailureException("down")).when(ownerRepository).deleteIfOwner(88L, "server-a:1");

        ownerService.release(88L, "server-a:1");

        verify(ownerRepository).deleteIfOwner(88L, "server-a:1");
    }

    @Test
    @DisplayName("Redis Owner가 있으면 조회한 Owner 값을 담아 명령을 발행한다")
    void dispatchesCommandWithCurrentOwner() {
        when(ownerRepository.find(88L)).thenReturn(Optional.of("server-b:7"));

        RecordingCommandDispatch dispatch = ownerService.dispatch(88L, RecordingCommand.STOP);

        assertThat(dispatch).isEqualTo(RecordingCommandDispatch.SENT);
        verify(commandPublisher).publish(new RecordingCommandMessage(88L, RecordingCommand.STOP, "server-b:7"));
    }

    @Test
    @DisplayName("Redis Owner가 없으면 명령을 발행하지 않는다")
    void doesNotDispatchWithoutOwner() {
        when(ownerRepository.find(88L)).thenReturn(Optional.empty());

        RecordingCommandDispatch dispatch = ownerService.dispatch(88L, RecordingCommand.PAUSE);

        assertThat(dispatch).isEqualTo(RecordingCommandDispatch.NO_OWNER);
        verifyNoInteractions(commandPublisher);
    }

    @Test
    @DisplayName("Owner 조회나 발행에 실패하면 실패 결과를 돌려준다")
    void returnsFailedWhenRedisFails() {
        when(ownerRepository.find(88L)).thenReturn(Optional.of("server-b:7"));
        doThrow(new RedisConnectionFailureException("down")).when(commandPublisher)
                .publish(new RecordingCommandMessage(88L, RecordingCommand.STOP, "server-b:7"));

        RecordingCommandDispatch dispatch = ownerService.dispatch(88L, RecordingCommand.STOP);

        assertThat(dispatch).isEqualTo(RecordingCommandDispatch.FAILED);
    }

    private AiLiveMeetingConnection connection() {
        return connection(88L);
    }

    private AiLiveMeetingConnection connection(Long recordingSessionId) {
        return new AiLiveMeetingConnection(recordingSessionId, 42L, "start-1");
    }
}
