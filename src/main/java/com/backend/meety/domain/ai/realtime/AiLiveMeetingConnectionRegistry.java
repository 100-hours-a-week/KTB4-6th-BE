package com.backend.meety.domain.ai.realtime;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class AiLiveMeetingConnectionRegistry {

    private final AiRecoveryRegistry recoveries;

    public AiLiveMeetingConnectionRegistry() { this.recoveries = null; }

    @org.springframework.beans.factory.annotation.Autowired
    public AiLiveMeetingConnectionRegistry(AiRecoveryRegistry recoveries) { this.recoveries = recoveries; }

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

    public void assignOwner(Long recordingSessionId, String owner) {
        find(recordingSessionId).ifPresent(connection -> {
            if (recoveries != null) {
                recoveries.find(recordingSessionId).ifPresent(context -> {
                    synchronized (context) {
                        if (!owner.equals(context.owner)) {
                            recoveries.remove(context);
                            if (context.current == connection && !connection.isClosed() && !connection.isRecoveryFenced()) {
                                connection.enableReplay();
                                connection.finishRecoveryReplay();
                            }
                        }
                    }
                });
            }
            connection.assignOwner(owner);
        });
    }

    public void remove(Long recordingSessionId, AiLiveMeetingConnection connection) {
        if (connections.remove(recordingSessionId, connection)) {
            if (recoveries != null) { recoveries.cancelConnection(connection); }
            connection.close();
        }
    }

    public List<AiLiveMeetingConnection> findAll() {
        return List.copyOf(connections.values());
    }

    public void detachRecovery(AiLiveMeetingConnection connection) {
        connection.fenceForRecovery();
        connections.remove(connection.recordingSessionId(), connection);
    }

    public int count() {
        return connections.size();
    }
}
