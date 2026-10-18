package com.backend.meety.domain.notification.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Slf4j
@Service
@RequiredArgsConstructor
public class NotificationSseService {

    private static final String CONNECTED_EVENT_NAME = "CONNECTED";
    private static final String NOTIFICATION_CREATED_EVENT_NAME = "NOTIFICATION_CREATED";
    private static final String HEARTBEAT_EVENT_NAME = "HEARTBEAT";
    private static final long RECONNECT_TIME_MILLIS = 3_000L;

    private final NotificationSseRegistry registry;
    private final NotificationSseEmitterFactory emitterFactory;
    private final NotificationSseConnectionIdGenerator connectionIdGenerator;
    private final Clock clock;

    public SseEmitter connect(Long userId) {
        String connectionId = connectionIdGenerator.generate();
        SseEmitter emitter = emitterFactory.create();
        registerLifecycleCallbacks(userId, connectionId, emitter);
        registry.register(userId, connectionId, emitter);
        log.info("[NOTIFICATION_SSE] 연결 등록 완료. userId={}, connectionId={}, userEmitters={}, totalEmitters={}",
                userId, connectionId, registry.count(userId), registry.countAll());

        try {
            sendConnectedEvent(emitter);
        } catch (IOException | RuntimeException e) {
            registry.remove(userId, connectionId, emitter);
            emitter.completeWithError(e);
            log.warn("알림 SSE 최초 연결 이벤트 전송에 실패했습니다. userId={}, connectionId={}",
                    userId, connectionId, e);
            throw new IllegalStateException("Notification SSE initial event send failed", e);
        }
        return emitter;
    }

    public int sendNotificationCreated(Long recipientUserId, NotificationCreatedSseEvent event) {
        return registry.sendToUser(
                recipientUserId,
                NOTIFICATION_CREATED_EVENT_NAME,
                event
        );
    }

    public int sendHeartbeat() {
        return registry.broadcast(
                HEARTBEAT_EVENT_NAME,
                NotificationSseHeartbeatEvent.heartbeat(LocalDateTime.now(clock))
        );
    }

    private void registerLifecycleCallbacks(Long userId, String connectionId, SseEmitter emitter) {
        emitter.onCompletion(() -> removeAndLog(userId, connectionId, emitter, "completion", null));
        emitter.onTimeout(() -> removeAndLog(userId, connectionId, emitter, "timeout", null));
        emitter.onError(throwable -> removeAndLog(userId, connectionId, emitter, "error", throwable));
    }

    private void removeAndLog(
            Long userId,
            String connectionId,
            SseEmitter emitter,
            String reason,
            Throwable throwable
    ) {
        registry.remove(userId, connectionId, emitter);
        if (throwable == null) {
            log.info("[NOTIFICATION_SSE] 연결이 종료되었습니다. userId={}, connectionId={}, reason={}, "
                            + "userEmitters={}, totalEmitters={}",
                    userId, connectionId, reason, registry.count(userId), registry.countAll());
            return;
        }
        log.warn("[NOTIFICATION_SSE] 연결이 오류로 종료되었습니다. userId={}, connectionId={}, reason={}, "
                        + "userEmitters={}, totalEmitters={}, cause={}",
                userId, connectionId, reason, registry.count(userId), registry.countAll(),
                throwable.getClass().getSimpleName());
    }

    private void sendConnectedEvent(SseEmitter emitter) throws IOException {
        emitter.send(SseEmitter.event()
                .name(CONNECTED_EVENT_NAME)
                .reconnectTime(RECONNECT_TIME_MILLIS)
                .data(NotificationSseConnectedEvent.connected(LocalDateTime.now(clock))));
    }
}
