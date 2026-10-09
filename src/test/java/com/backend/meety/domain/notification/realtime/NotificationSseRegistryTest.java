package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseRegistryTest {

    @Test
    @DisplayName("사용자별로 여러 SSE 연결을 분리해 등록한다")
    void registerMultipleEmittersPerUser() {
        // 테스트 목적:
        // 동일 사용자의 여러 탭과 다른 사용자의 연결이 서로 덮어쓰지 않고
        // userId와 connectionId 기준으로 독립 관리되는지 검증한다.

        // given
        NotificationSseRegistry registry = new NotificationSseRegistry();
        TestSseEmitter firstTab = new TestSseEmitter();
        TestSseEmitter secondTab = new TestSseEmitter();
        TestSseEmitter otherUser = new TestSseEmitter();

        // when
        registry.register(1L, "conn-1", firstTab);
        registry.register(1L, "conn-2", secondTab);
        registry.register(2L, "conn-3", otherUser);

        // then
        assertThat(registry.find(1L, "conn-1")).contains(firstTab);
        assertThat(registry.find(1L, "conn-2")).contains(secondTab);
        assertThat(registry.find(2L, "conn-3")).contains(otherUser);
        assertThat(registry.count(1L)).isEqualTo(2);
        assertThat(registry.count(2L)).isOne();
        assertThat(registry.countAll()).isEqualTo(3);
    }

    @Test
    @DisplayName("한 사용자에게만 알림 이벤트를 전송한다")
    void sendToUserTargetsOnlyRecipientEmitters() {
        // 테스트 목적:
        // 특정 사용자에게 알림 SSE 이벤트를 전송할 때
        // 같은 사용자의 모든 연결에 전달하고 다른 사용자의 연결에는 전달하지 않는지 검증한다.

        // given
        NotificationSseRegistry registry = new NotificationSseRegistry();
        TestSseEmitter firstTab = new TestSseEmitter();
        TestSseEmitter secondTab = new TestSseEmitter();
        TestSseEmitter otherUser = new TestSseEmitter();
        Object payload = "created";
        registry.register(1L, "conn-1", firstTab);
        registry.register(1L, "conn-2", secondTab);
        registry.register(2L, "conn-3", otherUser);

        // when
        int targetCount = registry.sendToUser(1L, "NOTIFICATION_CREATED", payload);

        // then
        assertThat(targetCount).isEqualTo(2);
        assertReceived(firstTab, "NOTIFICATION_CREATED", payload);
        assertReceived(secondTab, "NOTIFICATION_CREATED", payload);
        assertThat(otherUser.sentData).isNull();
        assertThat(registry.countAll()).isEqualTo(3);
    }

    @Test
    @DisplayName("일부 emitter 전송 실패는 해당 연결만 제거한다")
    void sendFailureRemovesOnlyFailedEmitter() {
        // 테스트 목적:
        // 동일 사용자의 여러 연결 중 하나가 전송 실패해도
        // 실패한 연결만 정리되고 다른 연결에는 이벤트가 계속 전달되는지 검증한다.

        // given
        NotificationSseRegistry registry = new NotificationSseRegistry();
        TestSseEmitter firstTab = new TestSseEmitter();
        TestSseEmitter failedTab = new TestSseEmitter();
        TestSseEmitter thirdTab = new TestSseEmitter();
        failedTab.sendFailure = new IOException("closed");
        registry.register(1L, "conn-1", firstTab);
        registry.register(1L, "conn-2", failedTab);
        registry.register(1L, "conn-3", thirdTab);

        // when
        registry.sendToUser(1L, "NOTIFICATION_CREATED", "created");

        // then
        assertReceived(firstTab, "NOTIFICATION_CREATED", "created");
        assertThat(failedTab.sendAttempted).isTrue();
        assertThat(failedTab.completed).isTrue();
        assertReceived(thirdTab, "NOTIFICATION_CREATED", "created");
        assertThat(registry.find(1L, "conn-1")).contains(firstTab);
        assertThat(registry.find(1L, "conn-2")).isEmpty();
        assertThat(registry.find(1L, "conn-3")).contains(thirdTab);
    }

    @Test
    @DisplayName("heartbeat 전송 실패도 해당 emitter만 제거한다")
    void heartbeatBroadcastRemovesOnlyFailedEmitter() {
        // 테스트 목적:
        // 전체 heartbeat 전송 중 특정 emitter가 실패해도
        // 다른 사용자와 다른 연결의 heartbeat 전송은 계속되는지 검증한다.

        // given
        NotificationSseRegistry registry = new NotificationSseRegistry();
        TestSseEmitter first = new TestSseEmitter();
        TestSseEmitter failed = new TestSseEmitter();
        TestSseEmitter otherUser = new TestSseEmitter();
        failed.sendFailure = new IOException("closed");
        registry.register(1L, "conn-1", first);
        registry.register(1L, "conn-2", failed);
        registry.register(2L, "conn-3", otherUser);

        // when
        int targetCount = registry.broadcast("HEARTBEAT", "heartbeat");

        // then
        assertThat(targetCount).isEqualTo(3);
        assertReceived(first, "HEARTBEAT", "heartbeat");
        assertThat(failed.sendAttempted).isTrue();
        assertThat(failed.completed).isTrue();
        assertReceived(otherUser, "HEARTBEAT", "heartbeat");
        assertThat(registry.find(1L, "conn-2")).isEmpty();
        assertThat(registry.countAll()).isEqualTo(2);
    }

    @Test
    @DisplayName("stale emitter cleanup은 현재 연결을 제거하지 않는다")
    void removeUsesEmitterIdentity() {
        // 테스트 목적:
        // lifecycle callback이 늦게 실행되어 오래된 emitter 제거를 시도해도
        // 같은 connectionId의 현재 emitter를 제거하지 않는지 검증한다.

        // given
        NotificationSseRegistry registry = new NotificationSseRegistry();
        TestSseEmitter stale = new TestSseEmitter();
        TestSseEmitter current = new TestSseEmitter();
        registry.register(1L, "conn-1", current);

        // when
        registry.remove(1L, "conn-1", stale);

        // then
        assertThat(registry.find(1L, "conn-1")).contains(current);
    }

    @Test
    @DisplayName("동시 등록과 제거에도 Registry count가 일관된다")
    void registryHandlesConcurrentAccess() {
        // 테스트 목적:
        // 여러 SSE 연결이 동시에 등록되고 일부가 제거되는 상황에서도
        // thread-safe 자료구조로 남은 연결 수가 일관되게 유지되는지 검증한다.

        // given
        NotificationSseRegistry registry = new NotificationSseRegistry();
        int total = 200;
        AtomicInteger removed = new AtomicInteger();

        // when
        IntStream.range(0, total).parallel()
                .forEach(index -> registry.register(1L, "conn-" + index, new TestSseEmitter()));
        registry.findByUserId(1L).parallelStream()
                .filter(connection -> Integer.parseInt(connection.connectionId().substring("conn-".length())) % 2 == 0)
                .forEach(connection -> {
                    registry.remove(connection.userId(), connection.connectionId(), connection.emitter());
                    removed.incrementAndGet();
                });

        // then
        assertThat(registry.count(1L)).isEqualTo(total - removed.get());
        assertThat(registry.countAll()).isEqualTo(total - removed.get());
    }

    private void assertReceived(TestSseEmitter emitter, String eventName, Object payload) {
        assertThat(emitter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(payload)
                .anyMatch(data -> data instanceof String value && value.startsWith("event:" + eventName + "\n"));
    }

    private static class TestSseEmitter extends SseEmitter {

        private boolean completed;
        private boolean sendAttempted;
        private IOException sendFailure;
        private Set<ResponseBodyEmitter.DataWithMediaType> sentData;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempted = true;
            if (sendFailure != null) {
                throw sendFailure;
            }
            sentData = builder.build();
        }

        @Override
        public void complete() {
            completed = true;
        }
    }
}
