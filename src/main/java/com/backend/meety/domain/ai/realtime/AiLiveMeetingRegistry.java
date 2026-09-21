package com.backend.meety.domain.ai.realtime;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class AiLiveMeetingRegistry {

    private final ConcurrentHashMap<Long, AiLiveMeetingConnection> connections = new ConcurrentHashMap<>();

    public boolean register(AiLiveMeetingConnection connection) {
        return connections.putIfAbsent(connection.recordingSessionId(), connection) == null;
    }

    public Optional<AiLiveMeetingConnection> find(Long recordingSessionId) {
        return Optional.ofNullable(connections.get(recordingSessionId));
    }

    public void remove(Long recordingSessionId) {
        connections.remove(recordingSessionId);
    }

    public int count() {
        return connections.size();
    }
}
