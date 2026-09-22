package com.backend.meety.domain.ai.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@RequiredArgsConstructor
public class AiLiveMeetingInboundHandler extends TextWebSocketHandler {

    private static final String SESSION_READY = "session.ready";
    private static final String ERROR = "error";
    private static final String READY = "READY";

    private final AiLiveMeetingConnection connection;
    private final AiLiveMeetingConnectionRegistry registry;
    private final ObjectMapper objectMapper;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText();
            if (SESSION_READY.equals(type)) {
                handleSessionReady(root);
                return;
            }
            if (ERROR.equals(type)) {
                log.warn("AI WebSocket error 이벤트를 수신했습니다. recordingSessionId={}, requestId={}, code={}, retryable={}",
                        connection.recordingSessionId(),
                        root.path("requestId").asText(),
                        root.path("payload").path("code").asText(),
                        root.path("payload").path("retryable").asText());
                cleanup();
                return;
            }
            log.warn("처리하지 않는 AI WebSocket 이벤트입니다. recordingSessionId={}, type={}",
                    connection.recordingSessionId(), type);
        } catch (Exception e) {
            log.warn("AI WebSocket 메시지 처리 중 예외가 발생했습니다. recordingSessionId={}",
                    connection.recordingSessionId(), e);
            cleanup();
            throw e;
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        cleanup();
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.warn("AI WebSocket transport 오류가 발생했습니다. recordingSessionId={}",
                connection.recordingSessionId(), exception);
        cleanup();
    }

    private void handleSessionReady(JsonNode root) {
        if (!matchesSessionStart(root)) {
            log.warn("AI session.ready 이벤트가 현재 connection과 일치하지 않습니다. recordingSessionId={}",
                    connection.recordingSessionId());
            return;
        }
        if (!READY.equals(root.path("payload").path("status").asText())) {
            log.warn("AI session.ready status가 READY가 아닙니다. recordingSessionId={}, status={}",
                    connection.recordingSessionId(), root.path("payload").path("status").asText());
            return;
        }
        connection.markReady();
    }

    private boolean matchesSessionStart(JsonNode root) {
        return SESSION_READY.equals(root.path("type").asText())
                && String.valueOf(connection.recordingSessionId()).equals(root.path("recordingSessionId").asText())
                && String.valueOf(connection.meetingId()).equals(root.path("meetingId").asText())
                && connection.sessionStartRequestId().equals(root.path("requestId").asText());
    }

    private void cleanup() {
        connection.close();
        WebSocketSession session = connection.webSocketSession();
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (Exception e) {
                log.warn("AI WebSocket cleanup 중 close에 실패했습니다. recordingSessionId={}",
                        connection.recordingSessionId(), e);
            }
        }
        registry.remove(connection.recordingSessionId(), connection);
    }
}
