package com.backend.meety.domain.recording.realtime;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioWebSocketCloser {

    private final AudioWebSocketRegistry registry;

    public void close(Long recordingSessionId) {
        registry.find(recordingSessionId)
                .ifPresent(session -> closeAndRemove(recordingSessionId, session));
        registry.removeStreamState(recordingSessionId);
    }

    private void closeAndRemove(Long recordingSessionId, WebSocketSession session) {
        try {
            session.close(CloseStatus.NORMAL);
        } catch (Exception e) {
            log.warn("녹음 종료 후 Audio WebSocket 종료에 실패했습니다. recordingSessionId={}",
                    recordingSessionId, e);
        } finally {
            registry.remove(recordingSessionId, session);
        }
    }
}
