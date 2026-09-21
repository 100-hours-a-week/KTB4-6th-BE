package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingAudioWebSocketCleanupListener {

    private final AudioWebSocketRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void closeAudioWebSocket(RecordingCompletedEvent event) {
        registry.find(event.recordingSessionId())
                .ifPresent(session -> closeAndRemove(event.recordingSessionId(), session));
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
