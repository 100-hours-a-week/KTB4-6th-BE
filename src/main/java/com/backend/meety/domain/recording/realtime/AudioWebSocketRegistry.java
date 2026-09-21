package com.backend.meety.domain.recording.realtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

@Component
public class AudioWebSocketRegistry {

    private final ConcurrentHashMap<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public boolean register(Long recordingSessionId, WebSocketSession session) {
        return sessions.putIfAbsent(recordingSessionId, session) == null;
    }

    public Optional<WebSocketSession> find(Long recordingSessionId) {
        return Optional.ofNullable(sessions.get(recordingSessionId));
    }

    public void remove(Long recordingSessionId, WebSocketSession session) {
        sessions.remove(recordingSessionId, session);
    }

    public boolean exists(Long recordingSessionId) {
        return sessions.containsKey(recordingSessionId);
    }

    public void close(Long recordingSessionId) {
        WebSocketSession session = sessions.remove(recordingSessionId);
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception ignored) {
        }
    }

    public int count() {
        return sessions.size();
    }
}
