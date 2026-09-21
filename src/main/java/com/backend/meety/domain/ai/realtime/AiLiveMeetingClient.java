package com.backend.meety.domain.ai.realtime;

import com.backend.meety.global.config.AiWebSocketProperties;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class AiLiveMeetingClient {

    private static final long CONNECT_TIMEOUT_SECONDS = 10L;

    private final AiWebSocketProperties properties;
    private final AiLiveMeetingRegistry registry;
    private final AiLiveMeetingInboundHandler inboundHandler;
    private final ObjectMapper objectMapper;

    public AiLiveMeetingConnection connect(Long meetingId, Long recordingSessionId, String audioFormat) {
        AiLiveMeetingConnection connection =
                new AiLiveMeetingConnection(objectMapper, meetingId, recordingSessionId, audioFormat);
        if (!registry.register(connection)) {
            return registry.find(recordingSessionId)
                    .orElseThrow(() -> new IllegalStateException("AI connection registration failed"));
        }
        try {
            StandardWebSocketClient client = new StandardWebSocketClient();
            WebSocketSession session = client.execute(new Handler(connection), properties.url())
                    .get(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            connection.attach(session);
            connection.sendStart();
            return connection;
        } catch (Exception e) {
            registry.remove(recordingSessionId);
            connection.close();
            throw new IllegalStateException("AI WebSocket connection failed", e);
        }
    }

    private class Handler extends TextWebSocketHandler {

        private final AiLiveMeetingConnection connection;

        private Handler(AiLiveMeetingConnection connection) {
            this.connection = connection;
        }

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) {
            inboundHandler.handle(connection, message.getPayload());
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
            registry.remove(connection.recordingSessionId());
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            inboundHandler.handleError(connection, exception);
        }
    }
}
