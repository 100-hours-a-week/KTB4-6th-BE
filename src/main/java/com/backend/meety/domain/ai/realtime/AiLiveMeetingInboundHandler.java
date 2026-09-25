package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.transcript.service.TranscriptService;
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
    private static final String SESSION_ENDED = "session.ended";
    private static final String ERROR = "error";
    private static final String TRANSCRIPT_SEGMENT_FINAL = AiTranscriptSegmentMessage.TYPE;
    private static final String READY = "READY";
    private static final String ENDED = "ENDED";

    private final AiLiveMeetingConnection connection;
    private final AiLiveMeetingConnectionService connectionService;
    private final ObjectMapper objectMapper;
    private final TranscriptService transcriptService;

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        log.info("AI 메시지 수신: {}", message.getPayload());
        try {
            JsonNode root = objectMapper.readTree(message.getPayload());
            String type = root.path("type").asText();
            if (SESSION_READY.equals(type)) {
                handleSessionReady(root);
                return;
            }
            if (SESSION_ENDED.equals(type)) {
                handleSessionEnded(root);
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
            if (TRANSCRIPT_SEGMENT_FINAL.equals(type)) {
                handleTranscriptSegmentFinal(root);
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
        if (connection.state() == AiLiveMeetingConnectionState.STOP_SENT) {
            log.warn("AI WebSocket이 session.ended 없이 종료되었습니다. recordingSessionId={}, closeStatus={}",
                    connection.recordingSessionId(), status);
        }
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
        connectionService.markReady(connection);
    }

    private void handleTranscriptSegmentFinal(JsonNode root) {
        AiTranscriptSegmentMessage message = AiTranscriptSegmentMessage.from(root);
        if (!connection.meetingId().equals(message.meetingId())
                || !connection.recordingSessionId().equals(message.recordingSessionId())) {
            throw new IllegalArgumentException("transcript event does not match current connection");
        }
        log.debug("AI transcript 수신. meetingId={}, recordingSessionId={}, sourceSegmentKey={}, sequenceNumber={}, contentLength={}",
                message.meetingId(), message.recordingSessionId(), message.sourceSegmentKey(),
                message.payload().sequenceNumber(), message.payload().content().length());
        if (connection.state() == AiLiveMeetingConnectionState.STOP_SENT) {
            log.debug("STOP_SENT 상태에서 AI transcript를 처리합니다. recordingSessionId={}, sourceSegmentKey={}",
                    connection.recordingSessionId(), message.sourceSegmentKey());
        }
        transcriptService.saveFinalSegment(message);
    }

    private void handleSessionEnded(JsonNode root) {
        if (!matchesSessionStop(root)) {
            log.warn("AI session.ended 이벤트가 현재 connection과 일치하지 않습니다. recordingSessionId={}",
                    connection.recordingSessionId());
            return;
        }
        if (!ENDED.equals(root.path("payload").path("status").asText())) {
            log.warn("AI session.ended status가 ENDED가 아닙니다. recordingSessionId={}, status={}",
                    connection.recordingSessionId(), root.path("payload").path("status").asText());
            return;
        }
        connection.markEnded();
        connectionService.notifyTranscriptFinalized(connection);
        cleanup();
    }

    private boolean matchesSessionStart(JsonNode root) {
        return SESSION_READY.equals(root.path("type").asText())
                && String.valueOf(connection.recordingSessionId()).equals(root.path("recordingSessionId").asText())
                && String.valueOf(connection.meetingId()).equals(root.path("meetingId").asText())
                && connection.sessionStartRequestId().equals(root.path("requestId").asText());
    }

    private boolean matchesSessionStop(JsonNode root) {
        return SESSION_ENDED.equals(root.path("type").asText())
                && String.valueOf(connection.recordingSessionId()).equals(root.path("recordingSessionId").asText())
                && String.valueOf(connection.meetingId()).equals(root.path("meetingId").asText())
                && connection.sessionStopRequestId() != null
                && connection.sessionStopRequestId().equals(root.path("requestId").asText());
    }

    private void cleanup() {
        connectionService.cleanup(connection);
    }
}
