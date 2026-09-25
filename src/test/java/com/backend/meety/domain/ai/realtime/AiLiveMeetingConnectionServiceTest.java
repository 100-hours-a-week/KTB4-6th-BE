package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.event.AiLiveMeetingReadyEvent;
import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.recording.realtime.AudioWebSocketContext;
import com.backend.meety.domain.transcript.service.TranscriptService;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Delayed;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiLiveMeetingConnectionServiceTest {

    private final List<Object> readyEvents = new ArrayList<>();
    private final AtomicBoolean readyResult = new AtomicBoolean();
    private final AiLiveMeetingConnectionRegistry registry = new AiLiveMeetingConnectionRegistry();
    private final TestAiWebSocketClient client = new TestAiWebSocketClient();
    private final TestAiStopTimeoutScheduler timeoutScheduler = new TestAiStopTimeoutScheduler();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final AiLiveMeetingConnectionService service = new AiLiveMeetingConnectionService(
            registry,
            client,
            new AiLiveMeetingProperties(URI.create("ws://localhost:8000/v1/live-meeting")),
            new FixedAiRequestIdGenerator(),
            timeoutScheduler,
            objectMapper,
            readyEvents::add,
            transcriptService
    );

    @Test
    void connectSendSessionStartAndMarkReadyWhenSessionReadyReceived() throws Exception {
        boolean started = service.start(context(AudioFormat.WEBM_OPUS));

        assertThat(started).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.START_SENT);
        assertThat(client.connectCount).isOne();
        assertSessionStart(client.session.sentMessages.getFirst(), "webm_opus");

        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {
                    "status": "READY",
                    "inputAudioFormat": "webm_opus",
                    "outputAudioFormat": "pcm_s16le",
                    "outputSampleRateHz": 16000,
                    "outputChannels": 1
                  }
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    void webmOpusSessionStartSucceeds() throws Exception {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();

        assertSessionStart(client.session.sentMessages.getFirst(), "webm_opus");
    }

    @Test
    void mp4AacSessionStartSucceeds() throws Exception {
        assertThat(service.start(context(AudioFormat.MP4_AAC))).isTrue();

        assertSessionStart(client.session.sentMessages.getFirst(), "mp4_aac");
    }

    @Test
    void duplicateAiConnectionDoesNotCreateNewConnection() {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();

        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isFalse();

        assertThat(client.connectCount).isOne();
        assertThat(registry.count()).isOne();
    }

    @Test
    void capacityExceededDoesNotLeaveStaleRegistryEntry() {
        client.failure = new RuntimeException("429 capacity_exceeded");

        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isFalse();

        assertThat(registry.find(88L)).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @Test
    void serviceUnavailableDoesNotLeaveStaleRegistryEntry() {
        client.failure = new RuntimeException("503 service_unavailable");

        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isFalse();

        assertThat(registry.find(88L)).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"meetingId", "recordingSessionId", "requestId"})
    void mismatchedSessionReadyDoesNotMarkReady(String mismatchField) throws Exception {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();
        String meetingId = "meetingId".equals(mismatchField) ? "43" : "42";
        String recordingSessionId = "recordingSessionId".equals(mismatchField) ? "89" : "88";
        String requestId = "requestId".equals(mismatchField) ? "other-request" : "start-test";

        client.receive(String.format("""
                {
                  "type": "session.ready",
                  "requestId": "%s",
                  "meetingId": "%s",
                  "recordingSessionId": "%s",
                  "payload": {"status": "READY"}
                }
                """, requestId, meetingId, recordingSessionId));

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.START_SENT);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    void aiErrorCleansRegistryWithoutChangingDbState() throws Exception {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();

        client.receive("""
                {
                  "type": "error",
                  "requestId": "start-test",
                  "payload": {
                    "code": "provider_unavailable",
                    "message": "provider is unavailable",
                    "retryable": true
                  }
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
    }

    @Test
    void connectionFailureDoesNotLeaveStaleRegistryEntry() {
        client.failure = new RuntimeException("connect failed");

        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isFalse();

        assertThat(registry.find(88L)).isEmpty();
    }

    @Test
    void stopSendsSessionStopThenEndedClosesAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();

        service.stop(88L);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertSessionStop(client.session.sentMessages.get(1));
        assertThat(timeoutScheduler.scheduledDelay).isEqualTo(Duration.ofSeconds(30));

        client.receive("""
                {
                  "type": "session.ended",
                  "requestId": "stop-test",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {
                    "status": "ENDED",
                    "lastSequence": null,
                    "audioDurationMs": 3600000
                  }
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(client.session.closeStatuses).contains(CloseStatus.NORMAL);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(timeoutScheduler.future.cancelled).isTrue();
    }

    @Test
    void stopPayloadContainsOnlyTypeAndRequestId() throws Exception {
        readyConnection();

        service.stop(88L);

        JsonNode root = objectMapper.readTree(client.session.sentMessages.get(1));
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.path("type").asText()).isEqualTo("session.stop");
        assertThat(root.path("requestId").asText()).isEqualTo("stop-test");
        assertThat(root.has("payload")).isFalse();
        assertThat(root.has("finalAudioEndMs")).isFalse();
    }

    @Test
    void stopWithMissingConnectionIsNoop() {
        service.stop(88L);

        assertThat(client.session.sentMessages).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOP_SENT", "ENDED", "CLOSED"})
    void duplicateStopDoesNotSendAgain(String state) throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);
        if ("ENDED".equals(state)) {
            connection.markEnded();
        }
        if ("CLOSED".equals(state)) {
            connection.close();
        }

        service.stop(88L);

        assertThat(client.session.sentMessages).hasSize(2);
    }

    @Test
    void stopBeforeReadyDoesNotSendSessionStopAndCleansRegistry() {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();

        service.stop(88L);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(client.session.sentMessages).hasSize(1);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(readyEvents).containsExactly(new MeetingTranscriptFinalizedEvent(42L, 88L));
    }

    @Test
    void stopSendFailurePublishesTranscriptFinalizedAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        client.session.open = false;

        service.stop(88L);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(readyEvents).contains(new MeetingTranscriptFinalizedEvent(42L, 88L));
    }

    @ParameterizedTest
    @ValueSource(strings = {"requestId", "meetingId", "recordingSessionId", "status"})
    void mismatchedSessionEndedDoesNotCleanupAsNormalEnded(String mismatchField) throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);
        String requestId = "requestId".equals(mismatchField) ? "other-stop" : "stop-test";
        String meetingId = "meetingId".equals(mismatchField) ? "43" : "42";
        String recordingSessionId = "recordingSessionId".equals(mismatchField) ? "89" : "88";
        String status = "status".equals(mismatchField) ? "FAILED" : "ENDED";

        client.receive(String.format("""
                {
                  "type": "session.ended",
                  "requestId": "%s",
                  "meetingId": "%s",
                  "recordingSessionId": "%s",
                  "payload": {"status": "%s"}
                }
                """, requestId, meetingId, recordingSessionId, status));

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(registry.find(88L)).contains(connection);
        assertThat(client.session.open).isTrue();
    }

    @Test
    void transcriptCommittedDuringStopSentDoesNotCleanupConnection() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.receive("""
                {
                  "type": "transcript.committed",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {
                    "sequenceNumber": 99,
                    "content": "last transcript",
                    "startedAtMs": 176200,
                    "endedAtMs": 179800,
                    "recognizedAt": "2026-09-21T05:30:04.500Z"
                  }
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(registry.find(88L)).contains(connection);
        assertThat(client.session.open).isTrue();
    }

    @Test
    void transcriptSegmentFinalIsSavedWhenReady() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();

        client.receive("""
                {
                  "type": "transcript.committed",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {
                    "sequenceNumber": 31,
                    "content": "final text",
                    "startedAtMs": 176200,
                    "endedAtMs": 179800,
                    "recognizedAt": "2026-09-21T05:30:04.500Z"
                  }
                }
                """);

        verify(transcriptService).saveFinalSegment(new AiTranscriptSegmentMessage(
                AiTranscriptSegmentMessage.TYPE,
                42L,
                88L,
                new AiTranscriptSegmentPayload(
                        31L,
                        "final text",
                        176200L,
                        179800L,
                        LocalDateTime.of(2026, 9, 21, 5, 30, 4, 500_000_000)
                )
        ));
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    void transcriptSegmentFinalIsSavedWhenStopSent() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.receive("""
                {
                  "type": "transcript.committed",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {
                    "sequenceNumber": 32,
                    "content": "last final text",
                    "startedAtMs": 180000,
                    "recognizedAt": "2026-09-21T05:30:05.000Z"
                  }
                }
                """);

        verify(transcriptService).saveFinalSegment(new AiTranscriptSegmentMessage(
                AiTranscriptSegmentMessage.TYPE,
                42L,
                88L,
                new AiTranscriptSegmentPayload(32L, "last final text", 180000L, null,
                        LocalDateTime.of(2026, 9, 21, 5, 30, 5))
        ));
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(registry.find(88L)).contains(connection);
    }

    @Test
    void errorDuringStopSentClosesAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.receive("""
                {
                  "type": "error",
                  "requestId": "stop-test",
                  "payload": {"code": "processing_timeout", "message": "timeout", "retryable": false}
                }
                """);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(timeoutScheduler.future.cancelled).isTrue();
    }

    @Test
    void stopTimeoutClosesAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        timeoutScheduler.runScheduledTask();

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
        assertThat(client.session.closeStatuses).contains(CloseStatus.NORMAL);
    }

    @Test
    void remoteCloseBeforeSessionEndedIsNotNormalEndedAndCleansRegistry() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.closeFromRemote(CloseStatus.NORMAL);

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
    }

    @Test
    void cleanupIsSafeWhenSessionEndedCloseAndTimeoutOverlap() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();
        service.stop(88L);

        client.receive("""
                {
                  "type": "session.ended",
                  "requestId": "stop-test",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {"status": "ENDED", "audioDurationMs": 1}
                }
                """);
        client.closeFromRemote(CloseStatus.NORMAL);
        timeoutScheduler.runScheduledTask();

        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.CLOSED);
        assertThat(registry.find(88L)).isEmpty();
    }

    private AiLiveMeetingConnection readyConnection() throws Exception {
        assertThat(service.start(context(AudioFormat.WEBM_OPUS))).isTrue();
        AiLiveMeetingConnection connection = registry.find(88L).orElseThrow();
        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": {"status": "READY"}
                }
                """);
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        return connection;
    }

    @Test
    @DisplayName("READY 상태에서 오디오를 전달하면 audio.meta와 바이너리가 순서대로 전송된다")
    void forwardAudioSendsMetaThenBinaryWhenReady() throws Exception {
        AiLiveMeetingConnection connection = readyConnection();

        boolean forwarded = service.forwardAudio(88L, new byte[]{1, 2, 3});

        assertThat(forwarded).isTrue();
        JsonNode meta = objectMapper.readTree(client.session.sentMessages.getLast());
        assertThat(meta.path("type").asText()).isEqualTo("audio.meta");
        assertThat(meta.path("payload").path("sequence").asLong()).isZero();
        assertThat(client.session.sentBinaries).hasSize(1);
        assertThat(client.session.sentBinaries.getFirst()).containsExactly(1, 2, 3);
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
    }

    @Test
    @DisplayName("오디오를 연속으로 전달하면 sequence가 0부터 순서대로 증가한다")
    void forwardAudioIncrementsSequence() throws Exception {
        readyConnection();

        service.forwardAudio(88L, new byte[]{1});
        service.forwardAudio(88L, new byte[]{2});
        service.forwardAudio(88L, new byte[]{3});

        List<Long> sequences = new ArrayList<>();
        for (String message : client.session.sentMessages) {
            JsonNode root = objectMapper.readTree(message);
            if ("audio.meta".equals(root.path("type").asText())) {
                sequences.add(root.path("payload").path("sequence").asLong());
            }
        }
        assertThat(sequences).containsExactly(0L, 1L, 2L);
        assertThat(client.session.sentBinaries).hasSize(3);
    }

    @Test
    @DisplayName("session.ready를 받기 전에 온 오디오는 전달하지 않는다")
    void forwardAudioRejectedBeforeReady() throws Exception {
        service.start(context(AudioFormat.WEBM_OPUS));

        boolean forwarded = service.forwardAudio(88L, new byte[]{1, 2, 3});

        assertThat(forwarded).isFalse();
        assertThat(client.session.sentBinaries).isEmpty();
    }

    @Test
    @DisplayName("AI 연결이 없으면 오디오를 전달하지 않는다")
    void forwardAudioRejectedWhenConnectionMissing() {
        boolean forwarded = service.forwardAudio(88L, new byte[]{1, 2, 3});

        assertThat(forwarded).isFalse();
    }

    @Test
    @DisplayName("session.ready를 받으면 AI 준비 완료 이벤트를 발행한다")
    void publishReadyEventWhenSessionReadyReceived() throws Exception {
        readyConnection();

        assertThat(readyEvents).hasSize(1);
        assertThat(readyEvents.getFirst())
                .isInstanceOf(AiLiveMeetingReadyEvent.class)
                .extracting("recordingSessionId")
                .isEqualTo(88L);
    }

    @Test
    @DisplayName("session.ready가 현재 connection과 일치하지 않으면 이벤트를 발행하지 않는다")
    void doesNotPublishReadyEventWhenSessionReadyMismatched() throws Exception {
        service.start(context(AudioFormat.WEBM_OPUS));

        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "other-request",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": { "status": "READY" }
                }
                """);

        assertThat(readyEvents).isEmpty();
    }

    @Test
    @DisplayName("session.ready를 받으면 핸드셰이크 대기가 성공으로 끝난다")
    void startAndAwaitReadySucceedsWhenSessionReadyArrives() throws Exception {
        Thread waiting = new Thread(() -> readyResult.set(service.startAndAwaitReady(context(AudioFormat.WEBM_OPUS))));
        waiting.start();
        awaitSessionStartSent();

        client.receive("""
                {
                  "type": "session.ready",
                  "requestId": "start-test",
                  "meetingId": "42",
                  "recordingSessionId": "88",
                  "payload": { "status": "READY" }
                }
                """);
        waiting.join(3_000);

        assertThat(readyResult.get()).isTrue();
        assertThat(registry.find(88L)).isPresent();
    }

    @Test
    @DisplayName("AI 연결에 실패하면 핸드셰이크 대기도 실패한다")
    void startAndAwaitReadyFailsWhenConnectionFails() {
        client.failure = new RuntimeException("connect failed");

        assertThat(service.startAndAwaitReady(context(AudioFormat.WEBM_OPUS))).isFalse();
        assertThat(registry.find(88L)).isEmpty();
    }

    private void awaitSessionStartSent() throws InterruptedException {
        for (int i = 0; i < 100 && client.session.sentMessages.isEmpty(); i++) {
            Thread.sleep(10);
        }
    }

    private AudioWebSocketContext context(AudioFormat audioFormat) {
        return new AudioWebSocketContext(7L, 42L, 88L, audioFormat);
    }

    private void assertSessionStart(String json, String audioFormat) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.size()).isEqualTo(5);
        assertThat(root.path("type").asText()).isEqualTo("session.start");
        assertThat(root.path("requestId").asText()).isEqualTo("start-test");
        assertThat(root.path("meetingId").isTextual()).isTrue();
        assertThat(root.path("meetingId").asText()).isEqualTo("42");
        assertThat(root.path("recordingSessionId").isTextual()).isTrue();
        assertThat(root.path("recordingSessionId").asText()).isEqualTo("88");
        assertThat(root.path("payload").size()).isOne();
        assertThat(root.path("payload").path("audioFormat").asText()).isEqualTo(audioFormat);
    }

    private void assertSessionStop(String json) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        assertThat(root.size()).isEqualTo(2);
        assertThat(root.path("type").asText()).isEqualTo("session.stop");
        assertThat(root.path("requestId").asText()).isEqualTo("stop-test");
    }

    private static class FixedAiRequestIdGenerator extends AiRequestIdGenerator {

        @Override
        public String sessionStartRequestId() {
            return "start-test";
        }

        @Override
        public String sessionStopRequestId() {
            return "stop-test";
        }
    }

    private static class TestAiWebSocketClient implements AiLiveMeetingWebSocketClient {

        private final TestWebSocketSession session = new TestWebSocketSession();
        private WebSocketHandler handler;
        private RuntimeException failure;
        private int connectCount;

        @Override
        public WebSocketSession connect(WebSocketHandler handler, URI uri) {
            connectCount++;
            if (failure != null) {
                throw failure;
            }
            this.handler = handler;
            return session;
        }

        private void receive(String payload) throws Exception {
            handler.handleMessage(session, new TextMessage(payload));
        }

        private void closeFromRemote(CloseStatus status) throws Exception {
            session.open = false;
            handler.afterConnectionClosed(session, status);
        }
    }

    private static class TestWebSocketSession implements WebSocketSession {

        private final WebSocketSession delegate = mock(WebSocketSession.class);
        private final List<String> sentMessages = new CopyOnWriteArrayList<>();
        private final List<byte[]> sentBinaries = new ArrayList<>();
        private final List<CloseStatus> closeStatuses = new ArrayList<>();
        private boolean open = true;

        private TestWebSocketSession() {
            when(delegate.getId()).thenReturn("ai-session");
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
            if (message instanceof BinaryMessage binaryMessage) {
                ByteBuffer payload = binaryMessage.getPayload();
                byte[] audio = new byte[payload.remaining()];
                payload.get(audio);
                sentBinaries.add(audio);
                return;
            }
            sentMessages.add((String) message.getPayload());
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }

        @Override
        public void close(org.springframework.web.socket.CloseStatus status) {
            open = false;
            closeStatuses.add(status);
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public java.net.URI getUri() {
            return delegate.getUri();
        }

        @Override
        public org.springframework.http.HttpHeaders getHandshakeHeaders() {
            return delegate.getHandshakeHeaders();
        }

        @Override
        public java.util.Map<String, Object> getAttributes() {
            return delegate.getAttributes();
        }

        @Override
        public java.security.Principal getPrincipal() {
            return delegate.getPrincipal();
        }

        @Override
        public java.net.InetSocketAddress getLocalAddress() {
            return delegate.getLocalAddress();
        }

        @Override
        public java.net.InetSocketAddress getRemoteAddress() {
            return delegate.getRemoteAddress();
        }

        @Override
        public String getAcceptedProtocol() {
            return delegate.getAcceptedProtocol();
        }

        @Override
        public void setTextMessageSizeLimit(int messageSizeLimit) {
            delegate.setTextMessageSizeLimit(messageSizeLimit);
        }

        @Override
        public int getTextMessageSizeLimit() {
            return delegate.getTextMessageSizeLimit();
        }

        @Override
        public void setBinaryMessageSizeLimit(int messageSizeLimit) {
            delegate.setBinaryMessageSizeLimit(messageSizeLimit);
        }

        @Override
        public int getBinaryMessageSizeLimit() {
            return delegate.getBinaryMessageSizeLimit();
        }

        @Override
        public java.util.List<org.springframework.web.socket.WebSocketExtension> getExtensions() {
            return delegate.getExtensions();
        }
    }

    private static class TestAiStopTimeoutScheduler implements AiStopTimeoutScheduler {

        private Runnable task;
        private Duration scheduledDelay;
        private TestScheduledFuture future;

        @Override
        public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
            this.task = task;
            this.scheduledDelay = delay;
            this.future = new TestScheduledFuture();
            return future;
        }

        private void runScheduledTask() {
            if (task != null && !future.cancelled) {
                task.run();
            }
        }
    }

    private static class TestScheduledFuture implements ScheduledFuture<Object> {

        private boolean cancelled;

        @Override
        public long getDelay(TimeUnit unit) {
            return 0;
        }

        @Override
        public int compareTo(Delayed other) {
            return 0;
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelled = true;
            return true;
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public boolean isDone() {
            return cancelled;
        }

        @Override
        public Object get() {
            return null;
        }

        @Override
        public Object get(long timeout, TimeUnit unit) {
            return null;
        }
    }
}
