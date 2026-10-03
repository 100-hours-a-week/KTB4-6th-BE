package com.backend.meety.domain.recording.realtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

@Slf4j
@Component
public class AudioWebSocketRegistry {

    private final ConcurrentHashMap<Long, WebSocketSession> sessions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, AudioStreamState> streamStates = new ConcurrentHashMap<>();

    public Optional<WebSocketSession> replace(Long recordingSessionId, WebSocketSession session) {
        return Optional.ofNullable(sessions.put(recordingSessionId, session));
    }

    public Optional<WebSocketSession> find(Long recordingSessionId) {
        return Optional.ofNullable(sessions.get(recordingSessionId));
    }

    public void remove(Long recordingSessionId, WebSocketSession session) {
        sessions.remove(recordingSessionId, session);
    }

    public void closeAndRemove(Long recordingSessionId, WebSocketSession session, CloseStatus status) {
        try {
            session.close(status);
        } catch (Exception e) {
            log.warn("Audio WebSocket 종료에 실패했습니다. recordingSessionId={}", recordingSessionId, e);
        } finally {
            remove(recordingSessionId, session);
        }
    }

    public int count() {
        return sessions.size();
    }

    public AudioStreamState streamState(Long recordingSessionId) {
        return streamStates.computeIfAbsent(recordingSessionId, ignored -> new AudioStreamState());
    }

    public void removeStreamState(Long recordingSessionId) {
        streamStates.remove(recordingSessionId);
    }
}
