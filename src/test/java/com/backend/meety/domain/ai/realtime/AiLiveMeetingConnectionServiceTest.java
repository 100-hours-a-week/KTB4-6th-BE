package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.recording.realtime.AudioWebSocketContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiLiveMeetingConnectionServiceTest {

    private final AiLiveMeetingConnectionRegistry registry = new AiLiveMeetingConnectionRegistry();
    private final TestAiWebSocketClient client = new TestAiWebSocketClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiLiveMeetingConnectionService service = new AiLiveMeetingConnectionService(
            registry,
            client,
            new AiLiveMeetingProperties(URI.create("ws://localhost:8000/v1/live-meeting")),
            new FixedAiRequestIdGenerator(),
            objectMapper
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

    private static class FixedAiRequestIdGenerator extends AiRequestIdGenerator {

        @Override
        public String sessionStartRequestId() {
            return "start-test";
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
    }

    private static class TestWebSocketSession implements WebSocketSession {

        private final WebSocketSession delegate = mock(WebSocketSession.class);
        private final List<String> sentMessages = new ArrayList<>();
        private boolean open = true;

        private TestWebSocketSession() {
            when(delegate.getId()).thenReturn("ai-session");
        }

        @Override
        public void sendMessage(WebSocketMessage<?> message) {
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
}
