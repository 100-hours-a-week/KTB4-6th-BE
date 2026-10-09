package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseServiceTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-09T04:30:12Z"),
            ZoneId.of("Asia/Seoul")
    );
    private static final LocalDateTime NOW = LocalDateTime.now(CLOCK);

    private final NotificationSseRegistry registry = new NotificationSseRegistry();
    private final TestNotificationSseEmitterFactory emitterFactory = new TestNotificationSseEmitterFactory();
    private final TestConnectionIdGenerator connectionIdGenerator = new TestConnectionIdGenerator();
    private final NotificationSseService service =
            new NotificationSseService(registry, emitterFactory, connectionIdGenerator, CLOCK);

    @Test
    @DisplayName("연결 성공 시 CONNECTED 이벤트와 reconnectTime을 전송한다")
    void connectSendsConnectedEventAndReconnectTime() {
        // 테스트 목적:
        // 전역 알림 SSE 연결이 등록된 뒤 초기 CONNECTED 이벤트를 전송하고
        // 브라우저 EventSource 재연결 간격 3초를 설정하는지 검증한다.

        // given
        TestSseEmitter emitter = emitterFactory.next();
        connectionIdGenerator.add("conn-1");

        // when
        SseEmitter result = service.connect(1L);

        // then
        assertThat(result).isSameAs(emitter);
        assertThat(registry.find(1L, "conn-1")).contains(emitter);
        assertThat(emitter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(NotificationSseConnectedEvent.connected(NOW))
                .anyMatch(data -> data instanceof String value && value.startsWith("event:CONNECTED\n"))
                .anyMatch(data -> data instanceof String value && value.contains("retry:3000\n"));
    }

    @Test
    @DisplayName("초기 CONNECTED 이벤트 전송 실패 시 연결을 정리한다")
    void connectFailureRemovesEmitter() {
        // 테스트 목적:
        // SSE 연결 등록 직후 CONNECTED 이벤트 전송에 실패하면
        // 실패한 emitter를 Registry에서 제거하고 error completion 처리하는지 검증한다.

        // given
        TestSseEmitter emitter = emitterFactory.next();
        emitter.sendFailure = new IOException("closed");
        connectionIdGenerator.add("conn-1");

        // when, then
        assertThatThrownBy(() -> service.connect(1L))
                .isInstanceOf(IllegalStateException.class);
        assertThat(registry.countAll()).isZero();
        assertThat(emitter.completedWithError).isTrue();
    }

    @Test
    @DisplayName("completion callback은 해당 연결을 정리한다")
    void completionCallbackRemovesEmitter() {
        // 테스트 목적:
        // 클라이언트 연결 종료로 onCompletion이 호출되면
        // 해당 SSE 연결만 Registry에서 제거되는지 검증한다.

        // given
        TestSseEmitter emitter = connect("conn-1", 1L);

        // when
        emitter.completion.run();

        // then
        assertThat(registry.find(1L, "conn-1")).isEmpty();
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("timeout callback은 해당 연결을 정리한다")
    void timeoutCallbackRemovesEmitter() {
        // 테스트 목적:
        // Servlet async timeout 또는 컨테이너 timeout으로 onTimeout이 호출되면
        // 해당 SSE 연결만 Registry에서 제거되는지 검증한다.

        // given
        TestSseEmitter emitter = connect("conn-1", 1L);

        // when
        emitter.timeout.run();

        // then
        assertThat(registry.find(1L, "conn-1")).isEmpty();
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("error callback은 해당 연결을 정리한다")
    void errorCallbackRemovesEmitter() {
        // 테스트 목적:
        // SSE 연결에서 오류 callback이 호출되면
        // 해당 연결만 Registry에서 제거되는지 검증한다.

        // given
        TestSseEmitter emitter = connect("conn-1", 1L);

        // when
        emitter.error.accept(new IOException("closed"));

        // then
        assertThat(registry.find(1L, "conn-1")).isEmpty();
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("알림 생성 이벤트는 수신자의 모든 연결에 전송된다")
    void sendNotificationCreated() {
        // 테스트 목적:
        // Notification 저장 성공 이후 생성된 이벤트가
        // 수신 사용자에게 열려 있는 모든 SSE 연결로 전달되는지 검증한다.

        // given
        TestSseEmitter firstTab = connect("conn-1", 1L);
        TestSseEmitter secondTab = connect("conn-2", 1L);
        TestSseEmitter otherUser = connect("conn-3", 2L);
        clearSent(firstTab, secondTab, otherUser);
        NotificationCreatedSseEvent event = notificationCreatedSseEvent(15L);

        // when
        int targetCount = service.sendNotificationCreated(1L, event);

        // then
        assertThat(targetCount).isEqualTo(2);
        assertReceived(firstTab, "NOTIFICATION_CREATED", event);
        assertReceived(secondTab, "NOTIFICATION_CREATED", event);
        assertThat(otherUser.sentData).isNull();
    }

    @Test
    @DisplayName("현재 인스턴스에 수신자 연결이 없으면 0건 전송으로 종료한다")
    void sendNotificationCreatedWithoutLocalEmitter() {
        // 테스트 목적:
        // Redis Subscriber가 현재 서버에 연결되지 않은 사용자 알림을 처리해도
        // 정상 상황으로 보고 오류 없이 0건 전송으로 종료되는지 검증한다.

        // given
        NotificationCreatedSseEvent event = notificationCreatedSseEvent(15L);

        // when
        int targetCount = service.sendNotificationCreated(1L, event);

        // then
        assertThat(targetCount).isZero();
        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("알림 SSE 전송 실패는 다른 연결 전송을 막지 않는다")
    void notificationSendFailureDoesNotBlockOtherEmitters() {
        // 테스트 목적:
        // NotificationCreated SSE 전송 중 일부 emitter가 실패해도
        // 실패한 emitter만 제거하고 같은 사용자의 다른 연결에는 이벤트를 전달하는지 검증한다.

        // given
        TestSseEmitter firstTab = connect("conn-1", 1L);
        TestSseEmitter failedTab = connect("conn-2", 1L);
        TestSseEmitter thirdTab = connect("conn-3", 1L);
        clearSent(firstTab, failedTab, thirdTab);
        failedTab.sendFailure = new IOException("closed");
        NotificationCreatedSseEvent event = notificationCreatedSseEvent(15L);

        // when
        service.sendNotificationCreated(1L, event);

        // then
        assertReceived(firstTab, "NOTIFICATION_CREATED", event);
        assertThat(failedTab.sendAttempted).isTrue();
        assertThat(failedTab.completed).isTrue();
        assertReceived(thirdTab, "NOTIFICATION_CREATED", event);
        assertThat(registry.find(1L, "conn-2")).isEmpty();
        assertThat(registry.count(1L)).isEqualTo(2);
    }

    @Test
    @DisplayName("heartbeat는 현재 Registry의 모든 연결에 전송된다")
    void sendHeartbeat() {
        // 테스트 목적:
        // 서버 주도 heartbeat 실행 시 현재 Registry에 등록된 모든 SSE 연결로
        // HEARTBEAT 이벤트와 전송 시각이 전달되는지 검증한다.

        // given
        TestSseEmitter first = connect("conn-1", 1L);
        TestSseEmitter second = connect("conn-2", 2L);
        clearSent(first, second);

        // when
        int targetCount = service.sendHeartbeat();

        // then
        NotificationSseHeartbeatEvent expected = NotificationSseHeartbeatEvent.heartbeat(NOW);
        assertThat(targetCount).isEqualTo(2);
        assertReceived(first, "HEARTBEAT", expected);
        assertReceived(second, "HEARTBEAT", expected);
    }

    @Test
    @DisplayName("heartbeat 전송 실패는 실패한 emitter만 정리한다")
    void heartbeatFailureRemovesOnlyFailedEmitter() {
        // 테스트 목적:
        // heartbeat 전송 중 한 연결이 실패해도
        // 다른 연결의 heartbeat 전송과 Registry 유지에 영향을 주지 않는지 검증한다.

        // given
        TestSseEmitter first = connect("conn-1", 1L);
        TestSseEmitter failed = connect("conn-2", 1L);
        TestSseEmitter otherUser = connect("conn-3", 2L);
        clearSent(first, failed, otherUser);
        failed.sendFailure = new IOException("closed");

        // when
        service.sendHeartbeat();

        // then
        NotificationSseHeartbeatEvent expected = NotificationSseHeartbeatEvent.heartbeat(NOW);
        assertReceived(first, "HEARTBEAT", expected);
        assertThat(failed.sendAttempted).isTrue();
        assertThat(failed.completed).isTrue();
        assertReceived(otherUser, "HEARTBEAT", expected);
        assertThat(registry.find(1L, "conn-2")).isEmpty();
        assertThat(registry.countAll()).isEqualTo(2);
    }

    private TestSseEmitter connect(String connectionId, Long userId) {
        TestSseEmitter emitter = emitterFactory.next();
        connectionIdGenerator.add(connectionId);
        service.connect(userId);
        return emitter;
    }

    private NotificationCreatedSseEvent notificationCreatedSseEvent(Long notificationId) {
        return new NotificationCreatedSseEvent(
                "NOTIFICATION_CREATED",
                notificationId,
                NotificationType.MEMBER_JOINED,
                "test2 님이 팀에 합류했습니다",
                NotificationReferenceType.TEAM,
                null,
                false,
                NOW
        );
    }

    private void clearSent(TestSseEmitter... emitters) {
        for (TestSseEmitter emitter : emitters) {
            emitter.sentData = null;
            emitter.sendAttempted = false;
        }
    }

    private void assertReceived(TestSseEmitter emitter, String eventName, Object payload) {
        assertThat(emitter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(payload)
                .anyMatch(data -> data instanceof String value && value.startsWith("event:" + eventName + "\n"));
    }

    private static class TestNotificationSseEmitterFactory extends NotificationSseEmitterFactory {

        private TestSseEmitter next;

        TestSseEmitter next() {
            next = new TestSseEmitter();
            return next;
        }

        @Override
        public SseEmitter create() {
            return next;
        }
    }

    private static class TestConnectionIdGenerator extends NotificationSseConnectionIdGenerator {

        private final Queue<String> connectionIds = new ArrayDeque<>();

        void add(String connectionId) {
            connectionIds.add(connectionId);
        }

        @Override
        public String generate() {
            return connectionIds.remove();
        }
    }

    private static class TestSseEmitter extends SseEmitter {

        private Runnable completion;
        private Runnable timeout;
        private Consumer<Throwable> error;
        private boolean completed;
        private boolean completedWithError;
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

        @Override
        public void completeWithError(Throwable ex) {
            completedWithError = true;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completion = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeout = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            error = callback;
        }
    }
}
