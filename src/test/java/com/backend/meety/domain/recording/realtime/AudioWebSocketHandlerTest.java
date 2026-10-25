package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.ai.realtime.AudioFormat;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class AudioWebSocketHandlerTest {

    private final AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
    private final AiLiveMeetingConnectionService aiConnectionService = mock(AiLiveMeetingConnectionService.class);
    private final AudioSequenceRepository sequenceRepository = mock(AudioSequenceRepository.class);
    private final AudioWebSocketHandler handler =
            new AudioWebSocketHandler(registry, aiConnectionService, new ObjectMapper());

    private final com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry aiRegistry =
            new com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry();
    private final com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection aiConnection =
            new com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection(88L, 42L, "test");
    private final com.backend.meety.domain.ai.realtime.AiAudioAckService ackService =
            new com.backend.meety.domain.ai.realtime.AiAudioAckService(aiRegistry, registry, sequenceRepository,
                    new com.backend.meety.domain.ai.realtime.AiAudioAckProperties(java.time.Duration.ofSeconds(5),
                            java.time.Duration.ofSeconds(1), 1024), java.time.Clock.systemUTC(),
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry(), event -> { });

    @BeforeEach
    void aiConnectionIsStreamable() {
        when(aiConnectionService.awaitStreamable(88L)).thenReturn(true);
        WebSocketSession aiSession = mock(WebSocketSession.class);
        when(aiSession.isOpen()).thenReturn(true);
        aiConnection.attach(aiSession);
        aiConnection.markReady();
        aiRegistry.reserve(aiConnection);
        when(sequenceRepository.saveIfOwner(eq(88L), eq(null), anyLong())).thenReturn(true);
    }

    @Test
    @DisplayName("연결이 성립하면 세션을 레지스트리에 등록한다")
    void registersSessionOnConnectionEstablished() throws Exception {
        // 테스트 목적:
        // 연결이 성립하면 세션을 레지스트리에 등록한다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());

        // when
        handler.afterConnectionEstablished(session);

        // then
        assertThat(registry.find(88L)).contains(session);
        // then
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("같은 녹음 세션의 이전 소켓이 남아 있으면 새 소켓으로 교체하고 이전 소켓을 닫는다")
    void replacesPreviousSessionAndClosesIt() throws Exception {
        // 테스트 목적:
        // 같은 녹음 세션의 이전 소켓이 남아 있으면 새 소켓으로 교체하고 이전 소켓을 닫는다는 동작을 검증한다.

        // given
        WebSocketSession previous = mock(WebSocketSession.class);
        registry.replace(88L, previous);
        WebSocketSession session = session(context());

        // when
        handler.afterConnectionEstablished(session);

        // then
        assertThat(registry.find(88L)).contains(session);
        // then
        verify(previous).close(AudioWebSocketHandler.SUPERSEDED);
        // then
        verify(session, never()).close(any(CloseStatus.class));
        // then
        verify(aiConnectionService, never()).stop(anyLong());
    }

    @Test
    @DisplayName("순번 헤더를 떼고 오디오만 AI로 전달한다")
    void forwardsAudioWithoutSequenceHeader() throws Exception {
        // 테스트 목적:
        // 순번 헤더를 떼고 오디오만 AI로 전달한다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());
        forwardSucceeds();

        // when
        handler.handleBinaryMessage(session, frame(1, 1_024));

        ArgumentCaptor<byte[]> audioCaptor = ArgumentCaptor.forClass(byte[].class);
        // then
        verify(aiConnectionService).forwardAudio(eq(88L), anyLong(), audioCaptor.capture());
        // then
        assertThat(audioCaptor.getValue()).hasSize(1_024);
        // then
        assertThat(registry.streamState(88L).lastProcessedSequence()).isZero();
    }

    @Test
    @DisplayName("AI로 전달한 오디오 바이트 통계를 유지한다")
    void recordsStatsWithAudioBytesOnly() throws Exception {
        // 테스트 목적:
        // AI로 전달한 오디오 바이트 통계를 유지한다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());
        forwardSucceeds();

        // when
        handler.handleBinaryMessage(session, frame(1, 1_024));
        // when
        handler.handleBinaryMessage(session, frame(2, 2_048));

        // then
        verify(session, never()).close(any(CloseStatus.class));
        AudioChunkStats stats = stats(session);
        // then
        assertThat(stats.chunkCount()).isEqualTo(2L);
        // then
        assertThat(stats.forwardedCount()).isEqualTo(2L);
        // then
        assertThat(stats.totalBytes()).isEqualTo(3_072L);
    }

    @Test
    @DisplayName("이미 처리한 순번은 AI로 다시 보내지 않는다")
    void skipsAlreadyProcessedSequence() throws Exception {
        // 테스트 목적:
        // 이미 처리한 순번은 AI로 다시 보내지 않는다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());
        forwardSucceeds();

        // when
        handler.handleBinaryMessage(session, frame(1, 16));
        // when
        handler.handleBinaryMessage(session, frame(2, 16));
        // when
        handler.handleBinaryMessage(session, frame(2, 16));
        // when
        handler.handleBinaryMessage(session, frame(1, 16));

        // then
        verify(aiConnectionService, times(2)).forwardAudio(eq(88L), anyLong(), any(byte[].class));
        // then
        assertThat(stats(session).duplicateCount()).isEqualTo(2L);
        // then
        assertThat(registry.streamState(88L).lastProcessedSequence()).isZero();
    }

    @Test
    @DisplayName("순번이 건너뛰면 빠진 구간은 무시하고 들어온 순번부터 이어서 처리한다")
    void continuesFromReceivedSequenceWhenGap() throws Exception {
        // 테스트 목적:
        // 순번이 건너뛰면 빠진 구간은 무시하고 들어온 순번부터 이어서 처리한다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());
        forwardSucceeds();

        // when
        handler.handleBinaryMessage(session, frame(1, 16));
        // when
        handler.handleBinaryMessage(session, frame(5, 16));
        // when
        handler.handleBinaryMessage(session, frame(3, 16));

        // then
        verify(aiConnectionService, times(2)).forwardAudio(eq(88L), anyLong(), any(byte[].class));
        // then
        assertThat(registry.streamState(88L).lastProcessedSequence()).isZero();
    }

    @Test
    @DisplayName("AI 전달에 실패하면 처리 순번을 올리지 않아 같은 순번을 다시 받을 수 있다")
    void doesNotAdvanceSequenceWhenForwardFails() throws Exception {
        // 테스트 목적:
        // AI 전달에 실패하면 처리 순번을 올리지 않아 같은 순번을 다시 받을 수 있다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());
        when(aiConnectionService.forwardAudio(eq(88L), anyLong(), any(byte[].class))).thenReturn(false, true);

        // when
        handler.handleBinaryMessage(session, frame(1, 16));
        // then
        assertThat(registry.streamState(88L).lastProcessedSequence()).isZero();

        // when
        handler.handleBinaryMessage(session, frame(1, 16));

        // then
        verify(aiConnectionService, times(2)).forwardAudio(eq(88L), anyLong(), any(byte[].class));
        // then
        assertThat(registry.streamState(88L).lastProcessedSequence()).isZero();
        // then
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("AI ACK와 Redis 저장이 완료된 청크 10개마다 FE 누적 ACK를 보낸다")
    void sendsCumulativeAckEveryTenChunks() throws Exception {
        // 테스트 목적:
        // AI ACK와 Redis 저장이 완료된 청크 10개마다 FE 누적 ACK를 보낸다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());
        registry.replace(88L, session);
        forwardSucceeds();

        for (long sequence = 1; sequence <= 25; sequence++) {
            handler.handleBinaryMessage(session, frame(sequence, 16));
            ack(sequence - 1);
        }

        ArgumentCaptor<WebSocketMessage<?>> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        // then
        verify(session, times(2)).sendMessage(messages.capture());
        // then
        assertThat(messages.getAllValues()).extracting(message -> (String) message.getPayload())
                .containsExactly("{\"type\":\"ack\",\"seq\":10}", "{\"type\":\"ack\",\"seq\":20}");
    }

    @Test
    @DisplayName("AI 전달만으로는 저장하지 않고 AI ACK 이후 Redis 순번을 저장한다")
    void savesSequenceForEachForwardedChunk() throws Exception {
        // 테스트 목적:
        // AI 전달만으로는 저장하지 않고 AI ACK 이후 Redis 순번을 저장한다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());
        forwardSucceeds();

        // when
        handler.handleBinaryMessage(session, frame(1, 16));
        // when
        handler.handleBinaryMessage(session, frame(2, 16));
        // when
        handler.handleBinaryMessage(session, frame(2, 16));

        // then
        verify(sequenceRepository, never()).saveIfOwner(anyLong(), any(), anyLong());
        ack(0);
        ack(1);
        // then
        verify(sequenceRepository).saveIfOwner(88L, null, 1L);
        // then
        verify(sequenceRepository).saveIfOwner(88L, null, 2L);
        // then
        verify(sequenceRepository, times(2)).saveIfOwner(eq(88L), eq(null), anyLong());
    }

    @Test
    @DisplayName("AI 전달에 실패한 청크는 순번을 Redis에 저장하지 않는다")
    void doesNotSaveSequenceWhenForwardFails() throws Exception {
        // 테스트 목적:
        // AI 전달에 실패한 청크는 순번을 Redis에 저장하지 않는다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());

        // when
        handler.handleBinaryMessage(session, frame(1, 16));

        // then
        verify(sequenceRepository, never()).save(anyLong(), anyLong());
    }

    @Test
    @DisplayName("ACK 차례에 순번 저장이 실패하면 ACK를 보내지 않고 처리는 계속한다")
    void skipsAckWhenSequenceSaveFails() throws Exception {
        // 테스트 목적:
        // ACK 차례에 순번 저장이 실패하면 ACK를 보내지 않고 처리는 계속한다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());
        registry.replace(88L, session);
        forwardSucceeds();
        doThrow(new RedisConnectionFailureException("down")).when(sequenceRepository).saveIfOwner(88L, null, 10L);

        for (long sequence = 1; sequence <= 20; sequence++) {
            handler.handleBinaryMessage(session, frame(sequence, 16));
            ack(sequence - 1);
        }

        // then
        assertThat(sentTexts(session)).containsExactly("{\"type\":\"ack\",\"seq\":11}", "{\"type\":\"ack\",\"seq\":20}");
        // then
        assertThat(registry.streamState(88L).lastProcessedSequence()).isEqualTo(20L);
        // then
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("소켓이 바뀌어도 녹음 단위 처리 순번을 이어서 쓴다")
    void keepsProcessedSequenceAcrossSockets() throws Exception {
        // 테스트 목적:
        // 소켓이 바뀌어도 녹음 단위 처리 순번을 이어서 쓴다는 동작을 검증한다.

        // given
        forwardSucceeds();
        WebSocketSession first = session(context());
        // when
        handler.afterConnectionEstablished(first);
        // when
        handler.handleBinaryMessage(first, frame(1, 16));
        // when
        handler.handleBinaryMessage(first, frame(2, 16));

        WebSocketSession second = session(context());
        // when
        handler.afterConnectionEstablished(second);
        // when
        handler.handleBinaryMessage(second, frame(2, 16));
        // when
        handler.handleBinaryMessage(second, frame(3, 16));

        // then
        verify(aiConnectionService, times(3)).forwardAudio(eq(88L), anyLong(), any(byte[].class));
        // then
        assertThat(registry.streamState(88L).lastProcessedSequence()).isZero();
    }

    @Test
    @DisplayName("연결되면 AI가 준비된 뒤 마지막 처리 순번과 함께 복구 시작을 알린다")
    void sendsRecoveryStartAfterAiIsReady() throws Exception {
        // 테스트 목적:
        // 연결되면 AI가 준비된 뒤 마지막 처리 순번과 함께 복구 시작을 알린다는 동작을 검증한다.

        // given
        registry.streamState(88L).markProcessed(104L);
        WebSocketSession session = session(context());

        // when
        handler.afterConnectionEstablished(session);

        // then
        assertThat(sentTexts(session)).containsExactly(
                "{\"type\":\"recovery.start\",\"lastProcessedSequence\":104}");
        // then
        verify(session, never()).close(any(CloseStatus.class));
    }

    @Test
    @DisplayName("AI가 준비되지 않으면 복구를 시작하지 않고 연결을 닫는다")
    void closesWhenAiIsNotReady() throws Exception {
        // 테스트 목적:
        // AI가 준비되지 않으면 복구를 시작하지 않고 연결을 닫는다는 동작을 검증한다.

        // given
        when(aiConnectionService.awaitStreamable(88L)).thenReturn(false);
        WebSocketSession session = session(context());

        // when
        handler.afterConnectionEstablished(session);

        // then
        verify(session).close(AudioWebSocketHandler.AI_NOT_READY);
        // then
        assertThat(sentTexts(session)).isEmpty();
    }

    @Test
    @DisplayName("복구가 끝나면 남은 ACK와 stream.ready를 보내고 이어서 오디오를 전달한다")
    void sendsStreamReadyAfterRecoveryFinished() throws Exception {
        // 테스트 목적:
        // 복구가 끝나면 디코더 리셋 없이 남은 ACK와 stream.ready를 보내고 이어서 오디오를 전달한다는 동작을 검증한다.

        // given
        forwardSucceeds();
        WebSocketSession session = connected();
        // when
        handler.handleBinaryMessage(session, frame(1, 16));
        // when
        handler.handleBinaryMessage(session, frame(2, 16));
        ack(0);
        ack(1);
        // when
        handler.handleTextMessage(session, recoveryFinished(2));
        // when
        handler.handleBinaryMessage(session, frame(3, 16));

        // then
        assertThat(sentTexts(session)).containsExactly(
                "{\"type\":\"recovery.start\",\"lastProcessedSequence\":0}",
                "{\"type\":\"ack\",\"seq\":2}",
                "{\"type\":\"stream.ready\"}");
        // then
        verify(aiConnectionService, times(3)).forwardAudio(eq(88L), anyLong(), any(byte[].class));
        // then
        assertThat(registry.streamState(88L).lastProcessedSequence()).isEqualTo(2L);
    }

    @Test
    @DisplayName("FE가 보낸 마지막 순번까지 처리되지 않았으면 전환하지 않고 복구를 다시 요청한다")
    void requestsRecoveryAgainWhenChunksRemain() throws Exception {
        // 테스트 목적:
        // FE가 보낸 마지막 순번까지 처리되지 않았으면 전환하지 않고 복구를 다시 요청한다는 동작을 검증한다.

        // given
        forwardSucceeds();
        WebSocketSession session = connected();
        // when
        handler.handleBinaryMessage(session, frame(1, 16));

        ack(0);
        // when
        handler.handleTextMessage(session, recoveryFinished(3));

        // then
        assertThat(sentTexts(session)).containsExactly(
                "{\"type\":\"recovery.start\",\"lastProcessedSequence\":0}",
                "{\"type\":\"recovery.start\",\"lastProcessedSequence\":1}");
    }

    @Test
    @DisplayName("이미 스트리밍 중인 소켓의 recovery.finished는 무시한다")
    void ignoresRecoveryFinishedWhenStreaming() throws Exception {
        // 테스트 목적:
        // 이미 스트리밍 중인 소켓의 recovery.finished는 무시한다는 동작을 검증한다.

        // given
        WebSocketSession session = connected();
        // when
        handler.handleTextMessage(session, recoveryFinished(0));

        // when
        handler.handleTextMessage(session, recoveryFinished(0));

        // then
        assertThat(sentTexts(session)).containsExactly(
                "{\"type\":\"recovery.start\",\"lastProcessedSequence\":0}",
                "{\"type\":\"ack\",\"seq\":0}",
                "{\"type\":\"stream.ready\"}");
    }

    @Test
    @DisplayName("알 수 없는 텍스트 메시지는 처리하지 않고 연결도 유지한다")
    void ignoresUnknownTextMessage() throws Exception {
        // 테스트 목적:
        // 알 수 없는 텍스트 메시지는 처리하지 않고 연결도 유지한다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());

        // when
        handler.handleTextMessage(session, new TextMessage("{\"type\":\"unknown\"}"));
        // when
        handler.handleTextMessage(session, new TextMessage("not json"));

        // then
        verify(session, never()).close(any(CloseStatus.class));
        // then
        verify(aiConnectionService, never()).forwardAudio(anyLong(), anyLong(), any(byte[].class));
    }

    @Test
    @DisplayName("허용 크기를 초과한 오디오 프레임은 연결을 닫는다")
    void closesSessionWhenFrameExceedsMaxSize() throws Exception {
        // 테스트 목적:
        // 허용 크기를 초과한 오디오 프레임은 연결을 닫는다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());

        // when
        handler.handleBinaryMessage(session, frame(1, AudioChunkPolicy.MAX_CHUNK_BYTES + 1));

        // then
        verify(session).close(CloseStatus.NOT_ACCEPTABLE.withReason("audio frame size not allowed"));
    }

    @Test
    @DisplayName("순번 헤더만 있고 오디오가 없으면 연결을 닫는다")
    void closesSessionWhenFrameHasNoAudio() throws Exception {
        // 테스트 목적:
        // 순번 헤더만 있고 오디오가 없으면 연결을 닫는다는 동작을 검증한다.

        // given
        WebSocketSession session = session(context());

        // when
        handler.handleBinaryMessage(session, frame(1, 0));

        // then
        verify(session).close(CloseStatus.NOT_ACCEPTABLE.withReason("audio frame size not allowed"));
        // then
        verify(aiConnectionService, never()).forwardAudio(anyLong(), anyLong(), any(byte[].class));
    }

    private void ack(long sequence) {
        ackService.receive(aiConnection, new ObjectMapper().readTree(
                "{\"recordingSessionId\":\"88\",\"payload\":{\"sequence\":" + sequence + "}}"),
                new ObjectMapper());
    }

    private WebSocketSession connected() throws Exception {
        WebSocketSession session = session(context());
        // when
        handler.afterConnectionEstablished(session);
        return session;
    }

    private TextMessage recoveryFinished(long lastSequence) {
        return new TextMessage("{\"type\":\"recovery.finished\",\"lastSequence\":" + lastSequence + "}");
    }

    private List<String> sentTexts(WebSocketSession session) throws Exception {
        ArgumentCaptor<WebSocketMessage<?>> messages = ArgumentCaptor.forClass(WebSocketMessage.class);
        // then
        verify(session, atLeast(0)).sendMessage(messages.capture());
        return messages.getAllValues().stream().map(message -> (String) message.getPayload()).toList();
    }

    private void forwardSucceeds() {
        when(aiConnectionService.forwardAudio(eq(88L), anyLong(), any(byte[].class)))
                .thenAnswer(call -> ackService.forwardAudio(aiConnection, new ObjectMapper(),
                        call.getArgument(1), call.getArgument(2)));
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
        return new AudioWebSocketContext(7L, 42L, 88L, AudioFormat.PCM_S16LE);
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
