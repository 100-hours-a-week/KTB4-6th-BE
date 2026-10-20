package com.backend.meety.domain.recording.owner;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.recording.realtime.AudioWebSocketCloser;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class RecordingCommandHandlerTest {

    private final AiLiveMeetingConnectionRegistry aiConnectionRegistry = new AiLiveMeetingConnectionRegistry();
    private final AiLiveMeetingConnectionService aiConnectionService = mock(AiLiveMeetingConnectionService.class);
    private final RecordingOwnerRepository ownerRepository = mock(RecordingOwnerRepository.class);
    private final AudioWebSocketCloser audioWebSocketCloser = mock(AudioWebSocketCloser.class);
    private final RecordingCommandHandler handler = new RecordingCommandHandler(
            aiConnectionRegistry, aiConnectionService, ownerRepository, audioWebSocketCloser);

    @Test
    @DisplayName("로컬 AI 연결과 Redis Owner가 모두 메시지의 Owner와 같으면 일시정지·재개를 실행한다")
    void executesPauseAndResumeForCurrentOwner() {
        givenLocalOwner("server-a:1");
        when(ownerRepository.find(88L)).thenReturn(Optional.of("server-a:1"));

        handler.handle(new RecordingCommandMessage(88L, RecordingCommand.PAUSE, "server-a:1"));
        handler.handle(new RecordingCommandMessage(88L, RecordingCommand.RESUME, "server-a:1"));

        verify(aiConnectionService).pause(88L);
        verify(aiConnectionService).resume(88L);
    }

    @Test
    @DisplayName("STOP은 AI에 session.stop을 보내고 브라우저 오디오 WebSocket을 닫는다")
    void stopSendsSessionStopAndClosesAudioWebSocket() {
        givenLocalOwner("server-a:1");
        when(ownerRepository.find(88L)).thenReturn(Optional.of("server-a:1"));

        handler.handle(new RecordingCommandMessage(88L, RecordingCommand.STOP, "server-a:1"));

        verify(aiConnectionService).stop(88L);
        verify(audioWebSocketCloser).close(88L);
    }

    @Test
    @DisplayName("로컬 AI 연결이 없으면 실행하지 않는다")
    void ignoresWithoutLocalConnection() {
        handler.handle(new RecordingCommandMessage(88L, RecordingCommand.STOP, "server-a:1"));

        verifyNoInteractions(aiConnectionService, audioWebSocketCloser, ownerRepository);
    }

    @Test
    @DisplayName("로컬 AI 연결의 Owner가 메시지와 다르면 실행하지 않는다")
    void ignoresWhenLocalOwnerDiffers() {
        givenLocalOwner("server-a:2");

        handler.handle(new RecordingCommandMessage(88L, RecordingCommand.STOP, "server-a:1"));

        verifyNoInteractions(aiConnectionService, audioWebSocketCloser);
    }

    @Test
    @DisplayName("Redis Owner가 다른 서버로 바뀌었으면 실행하지 않는다")
    void ignoresWhenRedisOwnerDiffers() {
        givenLocalOwner("server-a:1");
        when(ownerRepository.find(88L)).thenReturn(Optional.of("server-b:3"));

        handler.handle(new RecordingCommandMessage(88L, RecordingCommand.STOP, "server-a:1"));

        verifyNoInteractions(aiConnectionService, audioWebSocketCloser);
    }

    @Test
    @DisplayName("Redis Owner를 확인하지 못하면 실행하지 않는다")
    void ignoresWhenRedisFails() {
        givenLocalOwner("server-a:1");
        when(ownerRepository.find(88L)).thenThrow(new RedisConnectionFailureException("down"));

        handler.handle(new RecordingCommandMessage(88L, RecordingCommand.STOP, "server-a:1"));

        verifyNoInteractions(aiConnectionService, audioWebSocketCloser);
    }

    private void givenLocalOwner(String owner) {
        AiLiveMeetingConnection connection = new AiLiveMeetingConnection(88L, 42L, "start-1");
        connection.assignOwner(owner);
        aiConnectionRegistry.reserve(connection);
    }
}
