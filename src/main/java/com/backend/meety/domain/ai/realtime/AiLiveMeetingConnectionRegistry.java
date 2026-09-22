package com.backend.meety.domain.ai.realtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class AiLiveMeetingConnectionRegistry {

    private final ConcurrentHashMap<Long, AiLiveMeetingConnection> connections = new ConcurrentHashMap<>();

    public synchronized boolean reserve(AiLiveMeetingConnection connection) {
        AiLiveMeetingConnection current = connections.get(connection.recordingSessionId());
        if (current != null && current.state() != AiLiveMeetingConnectionState.CLOSED) {
            return false;
        }
        connections.put(connection.recordingSessionId(), connection);
        return true;
    }

    public Optional<AiLiveMeetingConnection> find(Long recordingSessionId) {
        return Optional.ofNullable(connections.get(recordingSessionId));
    }

    public void remove(Long recordingSessionId, AiLiveMeetingConnection connection) {
        connections.remove(recordingSessionId, connection);
    }

    public int count() {
        return connections.size();
    }
}
