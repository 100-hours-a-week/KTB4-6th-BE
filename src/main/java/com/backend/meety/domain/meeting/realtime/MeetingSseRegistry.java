package com.backend.meety.domain.meeting.realtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
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

    public void complete(Long meetingId, Long userId) {
        ConcurrentHashMap<Long, SseEmitter> meetingEmitters = emitters.get(meetingId);
        if (meetingEmitters == null) {
            return;
        }
        SseEmitter emitter = meetingEmitters.remove(userId);
        if (meetingEmitters.isEmpty()) {
            emitters.remove(meetingId, meetingEmitters);
        }
        completeEmitter(meetingId, userId, emitter);
    }

    public void completeAll(Long meetingId) {
        ConcurrentHashMap<Long, SseEmitter> meetingEmitters = emitters.remove(meetingId);
        if (meetingEmitters == null) {
            return;
        }
        meetingEmitters.forEach((userId, emitter) -> completeEmitter(meetingId, userId, emitter));
        meetingEmitters.clear();
    }

    public void sendAndCompleteAll(Long meetingId, String eventName, Object data) {
        ConcurrentHashMap<Long, SseEmitter> meetingEmitters = emitters.get(meetingId);
        if (meetingEmitters == null) {
            return;
        }
        List<Map.Entry<Long, SseEmitter>> targets = new ArrayList<>(meetingEmitters.entrySet());
        targets.forEach(entry -> sendAndCompleteEmitter(meetingId, entry.getKey(), entry.getValue(), eventName, data));
        emitters.remove(meetingId, meetingEmitters);
    }

    public int broadcast(Long meetingId, String eventName, Object data) {
        ConcurrentHashMap<Long, SseEmitter> meetingEmitters = emitters.get(meetingId);
        if (meetingEmitters == null) {
            return 0;
        }
        List<Map.Entry<Long, SseEmitter>> targets = new ArrayList<>(meetingEmitters.entrySet());
        targets.forEach(entry -> broadcastEmitter(meetingId, entry.getKey(), entry.getValue(), eventName, data));
        return targets.size();
    }

    public Collection<SseEmitter> findAll(Long meetingId) {
        return Optional.ofNullable(emitters.get(meetingId))
                .<Collection<SseEmitter>>map(meetingEmitters -> new ArrayList<>(meetingEmitters.values()))
                .orElseGet(Collections::emptyList);
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

    private void completeEmitter(Long meetingId, Long userId, SseEmitter emitter) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.complete();
        } catch (RuntimeException e) {
            log.warn("SSE 연결 종료에 실패했습니다. meetingId={}, userId={}", meetingId, userId, e);
        }
    }

    private void sendAndCompleteEmitter(
            Long meetingId,
            Long userId,
            SseEmitter emitter,
            String eventName,
            Object data
    ) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException | RuntimeException e) {
            log.warn("SSE 이벤트 전송에 실패했습니다. meetingId={}, userId={}, eventName={}",
                    meetingId, userId, eventName, e);
        } finally {
            completeEmitter(meetingId, userId, emitter);
            remove(meetingId, userId, emitter);
        }
    }

    private void broadcastEmitter(
            Long meetingId,
            Long userId,
            SseEmitter emitter,
            String eventName,
            Object data
    ) {
        if (emitter == null) {
            return;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(data));
        } catch (IOException | RuntimeException e) {
            log.warn("SSE broadcast 전송에 실패했습니다. meetingId={}, userId={}, eventName={}",
                    meetingId, userId, eventName, e);
            completeEmitter(meetingId, userId, emitter);
            remove(meetingId, userId, emitter);
        }
    }
}
