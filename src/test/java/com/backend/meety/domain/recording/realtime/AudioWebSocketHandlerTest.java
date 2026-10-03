package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class AudioWebSocketHandlerTest {

    private final AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
    private final AiLiveMeetingConnectionService aiConnectionService = mock(AiLiveMeetingConnectionService.class);
    private final AudioWebSocketHandler handler =
            new AudioWebSocketHandler(registry, aiConnectionService, new ObjectMapper());

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

        handler.handleBinaryMessage(session, frame(1, 16));

        verify(aiConnectionService).forwardAudio(eq(88L), eq(3L), any(byte[].class));
    }

    @Test
    @DisplayName("순번 헤더를 떼고 오디오만 AI로 전달한다")
    void forwardsAudioWithoutSequenceHeader() throws Exception {
        WebSocketSession session = session(context());
        forwardSucceeds();

        handler.handleBinaryMessage(session, frame(1, 1_024));

        ArgumentCaptor<byte[]> audioCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(aiConnectionService).forwardAudio(eq(88L), eq(0L), audioCaptor.capture());
        assertThat(audioCaptor.getValue()).hasSize(1_024);
        assertThat(registry.streamState(88L).lastProcessedSequence()).isEqualTo(1L);
    }

    @Test
    void recordsStatsWithAudioBytesOnly() throws Exception {
        WebSocketSession session = session(context());
        forwardSucceeds();

        handler.handleBinaryMessage(session, frame(1, 1_024));
        handler.handleBinaryMessage(session, frame(2, 2_048));

        verify(session, never()).close(any(CloseStatus.class));
        AudioChunkStats stats = stats(session);
        assertThat(stats.chunkCount()).isEqualTo(2L);
        assertThat(stats.forwardedCount()).isEqualTo(2L);
        assertThat(stats.totalBytes()).isEqualTo(3_072L);
    }

    @Test
    @DisplayName("이미 처리한 순번은 AI로 다시 보내지 않는다")
    void skipsAlreadyProcessedSequence() throws Exception {
        WebSocketSession session = session(context());
        forwardSucceeds();

        handler.handleBinaryMessage(session, frame(1, 16));
        handler.handleBinaryMessage(session, frame(2, 16));
        handler.handleBinaryMessage(session, frame(2, 16));
        handler.handleBinaryMessage(session, frame(1, 16));

        verify(aiConnectionService, times(2)).forwardAudio(eq(88L), eq(0L), any(byte[].class));
        assertThat(stats(session).duplicateCount()).isEqualTo(2L);
        assertThat(registry.streamState(88L).lastProcessedSequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("순번이 건너뛰면 빠진 구간은 무시하고 들어온 순번부터 이어서 처리한다")
    void continuesFromReceivedSequenceWhenGap() throws Exception {
        WebSocketSession session = session(context());
        forwardSucceeds();

        handler.handleBinaryMessage(session, frame(1, 16));
        handler.handleBinaryMessage(session, frame(5, 16));
        handler.handleBinaryMessage(session, frame(3, 16));

        verify(aiConnectionService, times(2)).forwardAudio(eq(88L), eq(0L), any(byte[].class));
        assertThat(registry.streamState(88L).lastProcessedSequence()).isEqualTo(5L);
    }

    @Test
    @DisplayName("AI 전달에 실패하면 처리 순번을 올리지 않아 같은 순번을 다시 받을 수 있다")
    void doesNotAdvanceSequenceWhenForwardFails() throws Exception {
        WebSocketSession session = session(context());
        when(aiConnectionService.forwardAudio(eq(88L), eq(0L), any(byte[].class))).thenReturn(false, true);

        handler.handleBinaryMessage(session, frame(1, 16));
        assertThat(registry.streamState(88L).lastProcessedSequence()).isZero();

        handler.handleBinaryMessage(session, frame(1, 16));

        verify(aiConnectionService, times(2)).forwardAudio(eq(88L), eq(0L), any(byte[].class));
        assertThat(registry.streamState(88L).lastProcessedSequence()).isEqualTo(1L);
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("10개를 처리할 때마다 마지막 처리 순번으로 누적 ACK를 보낸다")
    void sendsCumulativeAckEveryTenChunks() throws Exception {
        WebSocketSession session = session(context());
        forwardSucceeds();

        for (long sequence = 1; sequence <= 25; sequence++) {
            handler.handleBinaryMessage(session, frame(sequence, 16));
        }

        ArgumentCaptor<WebSocketMessage<?>> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session, times(2)).sendMessage(messages.capture());
        assertThat(messages.getAllValues()).extracting(message -> (String) message.getPayload())
                .containsExactly("{\"type\":\"ack\",\"seq\":10}", "{\"type\":\"ack\",\"seq\":20}");
    }

    @Test
    @DisplayName("소켓이 바뀌어도 녹음 단위 처리 순번을 이어서 쓴다")
    void keepsProcessedSequenceAcrossSockets() throws Exception {
        forwardSucceeds();
        WebSocketSession first = session(context());
        handler.afterConnectionEstablished(first);
        handler.handleBinaryMessage(first, frame(1, 16));
        handler.handleBinaryMessage(first, frame(2, 16));

        WebSocketSession second = session(context());
        handler.afterConnectionEstablished(second);
        handler.handleBinaryMessage(second, frame(2, 16));
        handler.handleBinaryMessage(second, frame(3, 16));

        verify(aiConnectionService, times(3)).forwardAudio(eq(88L), eq(0L), any(byte[].class));
        assertThat(registry.streamState(88L).lastProcessedSequence()).isEqualTo(3L);
    }

    @Test
    @DisplayName("텍스트 메시지는 처리하지 않고 연결도 유지한다")
    void ignoresTextMessage() throws Exception {
        WebSocketSession session = session(context());

        handler.handleTextMessage(session, new TextMessage("{\"type\":\"unknown\"}"));

        verify(session, never()).close(any(CloseStatus.class));
        verify(aiConnectionService, never()).forwardAudio(anyLong(), anyLong(), any(byte[].class));
    }

    @Test
    void closesSessionWhenFrameExceedsMaxSize() throws Exception {
        WebSocketSession session = session(context());

        handler.handleBinaryMessage(session, frame(1, AudioChunkPolicy.MAX_CHUNK_BYTES + 1));

        verify(session).close(CloseStatus.NOT_ACCEPTABLE.withReason("audio frame size not allowed"));
    }

    @Test
    @DisplayName("순번 헤더만 있고 오디오가 없으면 연결을 닫는다")
    void closesSessionWhenFrameHasNoAudio() throws Exception {
        WebSocketSession session = session(context());

        handler.handleBinaryMessage(session, frame(1, 0));

        verify(session).close(CloseStatus.NOT_ACCEPTABLE.withReason("audio frame size not allowed"));
        verify(aiConnectionService, never()).forwardAudio(anyLong(), anyLong(), any(byte[].class));
    }

    private void forwardSucceeds() {
        when(aiConnectionService.forwardAudio(eq(88L), eq(0L), any(byte[].class))).thenReturn(true);
    }

    private BinaryMessage frame(long sequence, int audioBytes) {
        ByteBuffer buffer = ByteBuffer.allocate(AudioChunkPolicy.SEQUENCE_BYTES + audioBytes);
        buffer.putLong(sequence);
        buffer.flip();
        return new BinaryMessage(buffer.limit(buffer.capacity()));
    }

    private AudioChunkStats stats(WebSocketSession session) {
        return (AudioChunkStats) session.getAttributes().get("audioChunkStats");
    }

    private AudioWebSocketContext context() {
        return new AudioWebSocketContext(7L, 42L, 88L, AudioFormat.WEBM_OPUS);
    }

    private WebSocketSession session(AudioWebSocketContext context) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(AudioWebSocketHandshakeInterceptor.CONTEXT_ATTRIBUTE, context);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);
        return session;
    }
}
