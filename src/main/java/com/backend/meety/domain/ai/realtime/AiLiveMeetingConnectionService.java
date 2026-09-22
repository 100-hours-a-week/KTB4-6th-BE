package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.recording.realtime.AudioWebSocketContext;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiLiveMeetingConnectionService {

    private final AiLiveMeetingConnectionRegistry registry;
    private final AiLiveMeetingWebSocketClient webSocketClient;
    private final AiLiveMeetingProperties properties;
    private final AiRequestIdGenerator requestIdGenerator;
    private final ObjectMapper objectMapper;

    public boolean start(AudioWebSocketContext context) {
        AiLiveMeetingConnection connection = new AiLiveMeetingConnection(
                context.recordingSessionId(),
                context.meetingId(),
                context.audioFormat(),
                requestIdGenerator.sessionStartRequestId()
        );
        if (!registry.reserve(connection)) {
            log.warn("AI WebSocket connection이 이미 존재합니다. recordingSessionId={}", context.recordingSessionId());
            return false;
        }
        try {
            WebSocketSession aiSession = webSocketClient.connect(
                    new AiLiveMeetingInboundHandler(connection, registry, objectMapper),
                    aiWebSocketUri()
            );
            connection.attach(aiSession);
            sendSessionStart(connection, aiSession);
            return true;
        } catch (Exception e) {
            log.warn("AI WebSocket 연결 또는 session.start 전송에 실패했습니다. recordingSessionId={}",
                    context.recordingSessionId(), e);
            cleanup(connection);
            return false;
        }
    }

    private URI aiWebSocketUri() {
        return properties.websocketUrl();
    }

    private void sendSessionStart(AiLiveMeetingConnection connection, WebSocketSession aiSession) throws Exception {
        String payload = objectMapper.writeValueAsString(AiSessionStartMessage.of(connection));
        aiSession.sendMessage(new TextMessage(payload));
        connection.markStartSent();
    }

    private void cleanup(AiLiveMeetingConnection connection) {
        connection.close();
        WebSocketSession aiSession = connection.webSocketSession();
        if (aiSession != null && aiSession.isOpen()) {
            try {
                aiSession.close();
            } catch (Exception e) {
                log.warn("AI WebSocket cleanup 중 close에 실패했습니다. recordingSessionId={}",
                        connection.recordingSessionId(), e);
            }
        }
        registry.remove(connection.recordingSessionId(), connection);
    }
}
