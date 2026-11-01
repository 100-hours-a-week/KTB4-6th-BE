package com.backend.meety.domain.ai.assistant;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Component
public class AssistantChatSseEmitterRegistry {

    private final ConcurrentHashMap<Long, Set<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter register(Long requestId, long timeoutMillis) {
        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emitters.computeIfAbsent(requestId, ignored -> ConcurrentHashMap.newKeySet()).add(emitter);
        Runnable remove = () -> remove(requestId, emitter);
        emitter.onCompletion(remove);
        emitter.onTimeout(remove);
        emitter.onError(ignored -> remove.run());
        return emitter;
    }

    public void send(Long requestId, String name, Object data) {
        Set<SseEmitter> targets = emitters.get(requestId);
        if (targets == null) return;
        for (SseEmitter emitter : targets) {
            sendOne(emitter, name, data);
        }
    }

    public void complete(Long requestId, String name, Object data) {
        Set<SseEmitter> targets = emitters.remove(requestId);
        if (targets == null) return;
        for (SseEmitter emitter : targets) {
            synchronized (emitter) {
                try {
                    emitter.send(SseEmitter.event().name(name).data(data));
                    emitter.complete();
                } catch (IOException | IllegalStateException ignored) {
                    emitter.completeWithError(ignored);
                }
            }
        }
    }

    private void sendOne(SseEmitter emitter, String name, Object data) {
        synchronized (emitter) {
            try {
                emitter.send(SseEmitter.event().name(name).data(data));
            } catch (IOException | IllegalStateException ignored) {
                emitter.completeWithError(ignored);
            }
        }
    }

    private void remove(Long requestId, SseEmitter emitter) {
        Set<SseEmitter> current = emitters.get(requestId);
        if (current != null) {
            current.remove(emitter);
            if (current.isEmpty()) emitters.remove(requestId, current);
        }
    }
}
