package com.backend.meety.domain.meeting.realtime;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class MeetingSseRegistry {

    private final ConcurrentHashMap<Long, ConcurrentHashMap<Long, SseEmitter>> emitters = new ConcurrentHashMap<>();

    public Optional<SseEmitter> register(Long meetingId, Long userId, SseEmitter emitter) {
        ConcurrentHashMap<Long, SseEmitter> meetingEmitters =
                emitters.computeIfAbsent(meetingId, ignored -> new ConcurrentHashMap<>());
        return Optional.ofNullable(meetingEmitters.put(userId, emitter));
    }

    public Optional<SseEmitter> find(Long meetingId, Long userId) {
        return Optional.ofNullable(emitters.get(meetingId))
                .map(meetingEmitters -> meetingEmitters.get(userId));
    }

    public void remove(Long meetingId, Long userId, SseEmitter emitter) {
        ConcurrentHashMap<Long, SseEmitter> meetingEmitters = emitters.get(meetingId);
        if (meetingEmitters == null) {
            return;
        }
        meetingEmitters.remove(userId, emitter);
        if (meetingEmitters.isEmpty()) {
            emitters.remove(meetingId, meetingEmitters);
        }
    }

    public int count(Long meetingId) {
        return Optional.ofNullable(emitters.get(meetingId))
                .map(Map::size)
                .orElse(0);
    }

    public int countAll() {
        return emitters.values().stream()
                .mapToInt(Map::size)
                .sum();
    }
}
