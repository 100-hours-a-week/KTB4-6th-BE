package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import java.nio.ByteBuffer;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioWebSocketHandler extends BinaryWebSocketHandler {

    private final AudioWebSocketRegistry registry;
    private final AudioForwardingService forwardingService;
    private final AiLiveMeetingClient aiLiveMeetingClient;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AudioWebSocketContext context = context(session);
        if (!registry.register(context.recordingSessionId(), session)) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("audio websocket already connected"));
            return;
        }
        try {
            aiLiveMeetingClient.connect(context.meetingId(), context.recordingSessionId(), context.audioFormat());
        } catch (RuntimeException e) {
            registry.remove(context.recordingSessionId(), session);
            session.close(CloseStatus.SERVER_ERROR.withReason("AI websocket connection failed"));
            log.warn("AI WebSocket 연결에 실패했습니다. recordingSessionId={}",
                    context.recordingSessionId(), e);
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
        AudioWebSocketContext context = context(session);
        ByteBuffer payload = message.getPayload();
        byte[] audio = new byte[payload.remaining()];
        payload.get(audio);
        forwardingService.forwardIfAllowed(context.recordingSessionId(), audio);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AudioWebSocketContext context = context(session);
        registry.remove(context.recordingSessionId(), session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        AudioWebSocketContext context = context(session);
        registry.remove(context.recordingSessionId(), session);
        log.warn("Audio WebSocket 오류가 발생했습니다. recordingSessionId={}",
                context.recordingSessionId(), exception);
    }

    private AudioWebSocketContext context(WebSocketSession session) {
        return (AudioWebSocketContext) session.getAttributes()
                .get(AudioWebSocketHandshakeInterceptor.CONTEXT_ATTRIBUTE);
    }
}
