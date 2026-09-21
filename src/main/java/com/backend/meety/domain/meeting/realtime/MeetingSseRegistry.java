package com.backend.meety.domain.meeting.realtime;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class MeetingSseRegistry {

    private static final long SSE_TIMEOUT_MILLIS = 30L * 60L * 1000L;

    private final ConcurrentHashMap<Long, Set<SseEmitter>> emittersByMeeting = new ConcurrentHashMap<>();

    public SseEmitter register(Long meetingId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        emittersByMeeting.computeIfAbsent(meetingId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        emitter.onCompletion(() -> remove(meetingId, emitter));
        emitter.onTimeout(() -> remove(meetingId, emitter));
        emitter.onError(ignored -> remove(meetingId, emitter));
        return emitter;
    }

    public void broadcast(Long meetingId, MeetingRealtimeEvent event) {
        Set<SseEmitter> emitters = emittersByMeeting.get(meetingId);
        if (emitters == null || emitters.isEmpty()) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.send(SseEmitter.event()
                        .name(event.type().name())
                        .data(event));
            } catch (IOException | IllegalStateException e) {
                remove(meetingId, emitter);
            }
        }
    }

    public void completeMeeting(Long meetingId) {
        Set<SseEmitter> emitters = emittersByMeeting.remove(meetingId);
        if (emitters == null) {
            return;
        }
        for (SseEmitter emitter : emitters) {
            try {
                emitter.complete();
            } catch (IllegalStateException ignored) {
            }
        }
    }

    public int count(Long meetingId) {
        Set<SseEmitter> emitters = emittersByMeeting.get(meetingId);
        return emitters == null ? 0 : emitters.size();
    }

    private void remove(Long meetingId, SseEmitter emitter) {
        Set<SseEmitter> emitters = emittersByMeeting.get(meetingId);
        if (emitters == null) {
            return;
        }
        emitters.remove(emitter);
        if (emitters.isEmpty()) {
            emittersByMeeting.remove(meetingId, emitters);
        }
    }
}
