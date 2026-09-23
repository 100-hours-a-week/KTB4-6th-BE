package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.event.AiLiveMeetingReadyEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioWebSocketReadyListener {

    private final AudioWebSocketRegistry registry;
    private final ObjectMapper objectMapper;

    @EventListener
    public void notifyReady(AiLiveMeetingReadyEvent event) {
        registry.find(event.recordingSessionId())
                .ifPresent(session -> sendReady(event.recordingSessionId(), session));
    }

    private void sendReady(Long recordingSessionId, WebSocketSession session) {
        try {
            String payload = objectMapper.writeValueAsString(AudioWebSocketReadyMessage.ready());
            session.sendMessage(new TextMessage(payload));
            log.info("Audio WebSocket ready 신호를 전송했습니다. recordingSessionId={}", recordingSessionId);
        } catch (Exception e) {
            log.warn("Audio WebSocket ready 신호 전송에 실패했습니다. recordingSessionId={}", recordingSessionId, e);
        }
    }
}
