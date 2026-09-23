package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.event.AiLiveMeetingReadyEvent;
import com.backend.meety.domain.recording.realtime.AudioWebSocketContext;
import java.time.Duration;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiLiveMeetingConnectionService {

    private static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);

    private final AiLiveMeetingConnectionRegistry registry;
    private final AiLiveMeetingWebSocketClient webSocketClient;
    private final AiLiveMeetingProperties properties;
    private final AiRequestIdGenerator requestIdGenerator;
    private final AiStopTimeoutScheduler stopTimeoutScheduler;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    void markReady(AiLiveMeetingConnection connection) {
        connection.markReady();
        eventPublisher.publishEvent(new AiLiveMeetingReadyEvent(connection.recordingSessionId()));
    }

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
                    new AiLiveMeetingInboundHandler(connection, this, objectMapper),
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

    /**
     * AI 연결이 READY일 때만 오디오를 전달한다. 전달하지 못한 청크는 버리고 브라우저 연결은 유지한다.
     */
    public boolean forwardAudio(Long recordingSessionId, byte[] audio) {
        AiLiveMeetingConnection connection = registry.find(recordingSessionId).orElse(null);
        if (connection == null) {
            return false;
        }
        try {
            return connection.forwardAudio(objectMapper, audio);
        } catch (Exception e) {
            log.warn("AI WebSocket 오디오 전달에 실패했습니다. recordingSessionId={}", recordingSessionId, e);
            return false;
        }
    }

    public void stop(Long recordingSessionId) {
        registry.find(recordingSessionId).ifPresentOrElse(this::stop,
                () -> log.info("종료할 AI WebSocket connection이 없습니다. recordingSessionId={}", recordingSessionId));
    }

    private URI aiWebSocketUri() {
        return properties.websocketUrl();
    }

    private void sendSessionStart(AiLiveMeetingConnection connection, WebSocketSession aiSession) throws Exception {
        String payload = objectMapper.writeValueAsString(AiSessionStartMessage.of(connection));
        aiSession.sendMessage(new TextMessage(payload));
        connection.markStartSent();
    }

    private void stop(AiLiveMeetingConnection connection) {
        String requestId = requestIdGenerator.sessionStopRequestId();
        if (!connection.markStopSent(requestId)) {
            log.info("AI WebSocket session.stop을 전송하지 않습니다. recordingSessionId={}, state={}",
                    connection.recordingSessionId(), connection.state());
            if (connection.state() == AiLiveMeetingConnectionState.CONNECTING
                    || connection.state() == AiLiveMeetingConnectionState.START_SENT) {
                cleanup(connection);
            }
            return;
        }
        connection.setStopTimeoutFuture(stopTimeoutScheduler.schedule(
                () -> handleStopTimeout(connection),
                STOP_TIMEOUT
        ));
        try {
            WebSocketSession aiSession = connection.webSocketSession();
            if (aiSession == null || !aiSession.isOpen()) {
                throw new IllegalStateException("AI WebSocket session is not open");
            }
            aiSession.sendMessage(new TextMessage(objectMapper.writeValueAsString(AiSessionStopMessage.of(connection))));
        } catch (Exception e) {
            log.warn("AI WebSocket session.stop 전송에 실패했습니다. recordingSessionId={}",
                    connection.recordingSessionId(), e);
            cleanup(connection);
        }
    }

    private void handleStopTimeout(AiLiveMeetingConnection connection) {
        if (connection.state() != AiLiveMeetingConnectionState.STOP_SENT) {
            return;
        }
        log.warn("AI WebSocket session.ended 대기 시간이 초과되었습니다. recordingSessionId={}",
                connection.recordingSessionId());
        cleanup(connection);
    }

    void cleanup(AiLiveMeetingConnection connection) {
        connection.cancelStopTimeout();
        connection.close();
        WebSocketSession aiSession = connection.webSocketSession();
        if (aiSession != null && aiSession.isOpen()) {
            try {
                aiSession.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.warn("AI WebSocket cleanup 중 close에 실패했습니다. recordingSessionId={}",
                        connection.recordingSessionId(), e);
            }
        }
        registry.remove(connection.recordingSessionId(), connection);
    }
}
