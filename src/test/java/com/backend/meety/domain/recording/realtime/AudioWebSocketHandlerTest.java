package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.ai.realtime.AudioFormat;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class AudioWebSocketHandlerTest {

    private final AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
    private final AiLiveMeetingConnectionService aiConnectionService = mock(AiLiveMeetingConnectionService.class);
    private final AudioWebSocketHandler handler = new AudioWebSocketHandler(registry, aiConnectionService);

    @Test
    @DisplayName("연결이 성립하면 세션을 레지스트리에 등록한다")
    void registersSessionOnConnectionEstablished() throws Exception {
        WebSocketSession session = session(context());

        handler.afterConnectionEstablished(session);

        assertThat(registry.find(88L)).contains(session);
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("같은 녹음 세션의 이전 소켓이 남아 있으면 새 소켓으로 교체하고 이전 소켓을 닫는다")
    void replacesPreviousSessionAndClosesIt() throws Exception {
        WebSocketSession previous = mock(WebSocketSession.class);
        registry.replace(88L, previous);
        WebSocketSession session = session(context());

        handler.afterConnectionEstablished(session);

        assertThat(registry.find(88L)).contains(session);
        verify(previous).close(AudioWebSocketHandler.SUPERSEDED);
        verify(session, never()).close(any(CloseStatus.class));
        verify(aiConnectionService, never()).stop(anyLong());
    }

    @Test
    @DisplayName("핸드셰이크에서 받은 스트림 세대로 오디오를 전달한다")
    void forwardsWithStreamEpochFromContext() throws Exception {
        WebSocketSession session = session(context().withStreamEpoch(3L));

        handler.handleBinaryMessage(session, chunk(16));

        verify(aiConnectionService).forwardAudio(eq(88L), eq(3L), any(byte[].class));
    }

    @Test
    void acceptsAudioChunkWithinAllowedSize() throws Exception {
        WebSocketSession session = session(context());
        when(aiConnectionService.forwardAudio(eq(88L), eq(0L), any(byte[].class))).thenReturn(true);

        handler.handleBinaryMessage(session, chunk(1_024));
        handler.handleBinaryMessage(session, chunk(2_048));

        verify(session, never()).close(any(CloseStatus.class));
        AudioChunkStats stats = (AudioChunkStats) session.getAttributes().get("audioChunkStats");
        assertThat(stats.chunkCount()).isEqualTo(2L);
        assertThat(stats.forwardedCount()).isEqualTo(2L);
        assertThat(stats.totalBytes()).isEqualTo(3_072L);
    }

    @Test
    @DisplayName("수신한 오디오 청크를 AI WebSocket으로 전달한다")
    void forwardsAudioChunkToAiConnection() throws Exception {
        WebSocketSession session = session(context());
        when(aiConnectionService.forwardAudio(eq(88L), eq(0L), any(byte[].class))).thenReturn(true);

        handler.handleBinaryMessage(session, chunk(1_024));

        ArgumentCaptor<byte[]> audioCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(aiConnectionService).forwardAudio(eq(88L), eq(0L), audioCaptor.capture());
        assertThat(audioCaptor.getValue()).hasSize(1_024);
    }

    @Test
    @DisplayName("AI 전달에 실패해도 브라우저 연결은 끊지 않는다")
    void keepsConnectionWhenAiForwardingFails() throws Exception {
        WebSocketSession session = session(context());
        when(aiConnectionService.forwardAudio(eq(88L), eq(0L), any(byte[].class))).thenReturn(false);

        handler.handleBinaryMessage(session, chunk(1_024));

        verify(session, never()).close(any(CloseStatus.class));
        AudioChunkStats stats = (AudioChunkStats) session.getAttributes().get("audioChunkStats");
        assertThat(stats.chunkCount()).isEqualTo(1L);
        assertThat(stats.forwardedCount()).isZero();
    }

    @Test
    void closesSessionWhenChunkExceedsMaxSize() throws Exception {
        WebSocketSession session = session(context());

        handler.handleBinaryMessage(session, chunk(AudioChunkPolicy.MAX_CHUNK_BYTES + 1));

        verify(session).close(CloseStatus.NOT_ACCEPTABLE.withReason("audio chunk size not allowed"));
    }

    @Test
    void closesSessionWhenChunkIsEmpty() throws Exception {
        WebSocketSession session = session(context());

        handler.handleBinaryMessage(session, chunk(0));

        verify(session).close(CloseStatus.NOT_ACCEPTABLE.withReason("audio chunk size not allowed"));
    }

    private BinaryMessage chunk(int bytes) {
        return new BinaryMessage(ByteBuffer.wrap(new byte[bytes]));
    }

    private AudioWebSocketContext context() {
        return new AudioWebSocketContext(7L, 42L, 88L, AudioFormat.WEBM_OPUS);
    }

    private WebSocketSession session(AudioWebSocketContext context) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(AudioWebSocketHandshakeInterceptor.CONTEXT_ATTRIBUTE, context);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
