package com.backend.meety.domain.notification.realtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Component
public class NotificationSseRegistry {

    private final ConcurrentHashMap<Long, ConcurrentHashMap<String, SseEmitter>> emitters =
            new ConcurrentHashMap<>();

    public void register(Long userId, String connectionId, SseEmitter emitter) {
        emitters.computeIfAbsent(userId, ignored -> new ConcurrentHashMap<>())
                .put(connectionId, emitter);
    }

    public void remove(Long userId, String connectionId, SseEmitter emitter) {
        ConcurrentHashMap<String, SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null) {
            return;
        }
        userEmitters.remove(connectionId, emitter);
        if (userEmitters.isEmpty()) {
            emitters.remove(userId, userEmitters);
        }
    }

    public int sendToUser(Long userId, String eventName, Object data) {
        List<NotificationSseConnection> targets = findByUserId(userId);
        targets.forEach(connection -> send(connection, eventName, data));
        return targets.size();
    }

    public int broadcast(String eventName, Object data) {
        List<NotificationSseConnection> targets = findAll();
        targets.forEach(connection -> send(connection, eventName, data));
        return targets.size();
    }

    public List<NotificationSseConnection> findByUserId(Long userId) {
        ConcurrentHashMap<String, SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters == null) {
            return List.of();
        }
        return userEmitters.entrySet().stream()
                .map(entry -> new NotificationSseConnection(userId, entry.getKey(), entry.getValue()))
                .toList();
    }

    public List<NotificationSseConnection> findAll() {
        List<NotificationSseConnection> snapshot = new ArrayList<>();
        emitters.forEach((userId, userEmitters) -> userEmitters.forEach(
                (connectionId, emitter) -> snapshot.add(new NotificationSseConnection(userId, connectionId, emitter))
        ));
        return snapshot;
    }

    public Optional<SseEmitter> find(Long userId, String connectionId) {
        return Optional.ofNullable(emitters.get(userId))
                .map(userEmitters -> userEmitters.get(connectionId));
    }

    public int count(Long userId) {
        return Optional.ofNullable(emitters.get(userId))
                .map(Map::size)
                .orElse(0);
    }

    public int countAll() {
        return emitters.values().stream()
                .mapToInt(Map::size)
                .sum();
    }

    private void send(NotificationSseConnection connection, String eventName, Object data) {
        try {
            connection.emitter().send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException | RuntimeException e) {
            log.warn("알림 SSE 이벤트 전송에 실패했습니다. userId={}, connectionId={}, eventName={}",
                    connection.userId(), connection.connectionId(), eventName, e);
            complete(connection);
            remove(connection.userId(), connection.connectionId(), connection.emitter());
        }
    }

    private void complete(NotificationSseConnection connection) {
        try {
            connection.emitter().complete();
        } catch (RuntimeException e) {
            log.warn("알림 SSE 연결 종료에 실패했습니다. userId={}, connectionId={}",
                    connection.userId(), connection.connectionId(), e);
        }
    }
}
