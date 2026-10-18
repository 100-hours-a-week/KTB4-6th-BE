package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.data.redis.connection.Message;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class NotificationRedisSubscriberTest {

    private static final Clock CLOCK = Clock.fixed(
            Instant.parse("2026-10-09T04:30:12Z"),
            ZoneId.of("Asia/Seoul")
    );

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("정상 Redis JSON 메시지를 NotificationSseService에 전달한다")
    void onMessageDelegatesToNotificationSseService() {
        // 테스트 목적:
        // Redis Subscriber가 JSON 메시지를 역직렬화한 뒤
        // Spring Event 재발행 없이 Local SSE 전송 서비스로 직접 전달하는지 검증한다.

        // given
        NotificationSseService notificationSseService = mock(NotificationSseService.class);
        NotificationRedisSubscriber subscriber =
                new NotificationRedisSubscriber(objectMapper, notificationSseService);
        NotificationRedisMessage redisMessage = notificationRedisMessage(1L);
        String json = objectMapper.writeValueAsString(redisMessage);

        // when
        subscriber.onMessage(redisMessage(json), null);

        // then
        ArgumentCaptor<NotificationCreatedSseEvent> eventCaptor =
                ArgumentCaptor.forClass(NotificationCreatedSseEvent.class);
        verify(notificationSseService).sendNotificationCreated(eq(1L), eventCaptor.capture());
        assertThat(eventCaptor.getValue()).isEqualTo(NotificationCreatedSseEvent.from(redisMessage));
    }

    @Test
    @DisplayName("현재 인스턴스에 수신자 연결이 없어도 Redis 메시지 처리는 정상 종료한다")
    void onMessageWithoutLocalEmitterDoesNotFail() {
        // 테스트 목적:
        // 멀티 인스턴스 환경에서 해당 사용자의 SSE 연결이 현재 서버에 없는 경우
        // 0건 전송으로 정상 처리되는지 검증한다.

        // given
        NotificationSseService notificationSseService = notificationSseService(new NotificationSseRegistry());
        NotificationRedisSubscriber subscriber =
                new NotificationRedisSubscriber(objectMapper, notificationSseService);
        String json = objectMapper.writeValueAsString(notificationRedisMessage(1L));

        // when, then
        assertThatCode(() -> subscriber.onMessage(redisMessage(json), null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("현재 인스턴스에 수신자 연결이 있으면 SSE 이벤트를 전송한다")
    void onMessageSendsToLocalEmitter() {
        // 테스트 목적:
        // Redis로 fan-out된 알림 메시지를 받은 인스턴스에 수신자 SSE 연결이 있으면
        // NOTIFICATION_CREATED 이벤트와 기존 payload 형식으로 전송되는지 검증한다.

        // given
        NotificationSseRegistry registry = new NotificationSseRegistry();
        TestNotificationSseEmitterFactory emitterFactory = new TestNotificationSseEmitterFactory();
        TestConnectionIdGenerator connectionIdGenerator = new TestConnectionIdGenerator();
        NotificationSseService notificationSseService =
                new NotificationSseService(registry, emitterFactory, connectionIdGenerator, CLOCK);
        NotificationRedisSubscriber subscriber =
                new NotificationRedisSubscriber(objectMapper, notificationSseService);
        TestSseEmitter emitter = emitterFactory.next();
        connectionIdGenerator.add("conn-1");
        notificationSseService.connect(1L);
        clearSent(emitter);
        NotificationRedisMessage redisMessage = notificationRedisMessage(1L);
        String json = objectMapper.writeValueAsString(redisMessage);

        // when
        subscriber.onMessage(redisMessage(json), null);

        // then
        assertReceived(emitter, "NOTIFICATION_CREATED", NotificationCreatedSseEvent.from(redisMessage));
    }

    @Test
    @DisplayName("recipientUserId가 없는 Redis 메시지는 폐기한다")
    void onMessageWithoutRecipientUserIdDiscardsMessage() {
        // 테스트 목적:
        // 수신 사용자 식별자가 없는 Redis 메시지는 Local SSE 대상으로 해석할 수 없으므로
        // SSE 전송 서비스 호출 없이 폐기되는지 검증한다.

        // given
        NotificationSseService notificationSseService = mock(NotificationSseService.class);
        NotificationRedisSubscriber subscriber =
                new NotificationRedisSubscriber(objectMapper, notificationSseService);
        String json = objectMapper.writeValueAsString(notificationRedisMessage(null));

        // when
        subscriber.onMessage(redisMessage(json), null);

        // then
        verify(notificationSseService, never())
                .sendNotificationCreated(any(Long.class), any(NotificationCreatedSseEvent.class));
    }

    @Test
    @DisplayName("malformed Redis 메시지는 로그 후 폐기한다")
    void malformedMessageDoesNotPropagate() {
        // 테스트 목적:
        // JSON 형식이 깨진 Redis 메시지를 수신해도 ListenerContainer까지 예외를 전파하지 않고
        // 해당 메시지만 폐기하는지 검증한다.

        // given
        NotificationSseService notificationSseService = mock(NotificationSseService.class);
        NotificationRedisSubscriber subscriber =
                new NotificationRedisSubscriber(objectMapper, notificationSseService);

        // when, then
        assertThatCode(() -> subscriber.onMessage(redisMessage("{malformed"), null))
                .doesNotThrowAnyException();
        verify(notificationSseService, never())
                .sendNotificationCreated(any(Long.class), any(NotificationCreatedSseEvent.class));
    }

    @Test
    @DisplayName("Subscriber 처리 실패는 ListenerContainer로 전파하지 않는다")
    void serviceFailureDoesNotPropagate() {
        // 테스트 목적:
        // Local SSE 전송 계층에서 예외가 발생해도 Redis listener thread를 중단시키지 않도록
        // Subscriber 밖으로 예외를 전파하지 않는지 검증한다.

        // given
        NotificationSseService notificationSseService = mock(NotificationSseService.class);
        when(notificationSseService.sendNotificationCreated(eq(1L), any(NotificationCreatedSseEvent.class)))
                .thenThrow(new IllegalStateException("sse failed"));
        NotificationRedisSubscriber subscriber =
                new NotificationRedisSubscriber(objectMapper, notificationSseService);
        String json = objectMapper.writeValueAsString(notificationRedisMessage(1L));

        // when, then
        assertThatCode(() -> subscriber.onMessage(redisMessage(json), null))
                .doesNotThrowAnyException();
    }

    private NotificationSseService notificationSseService(NotificationSseRegistry registry) {
        return new NotificationSseService(
                registry,
                new NotificationSseEmitterFactory(),
                new NotificationSseConnectionIdGenerator(),
                CLOCK
        );
    }

    private Message redisMessage(String payload) {
        return new DefaultMessage(
                NotificationRedisChannels.NOTIFICATION_SSE.getBytes(StandardCharsets.UTF_8),
                payload.getBytes(StandardCharsets.UTF_8)
        );
    }

    private NotificationRedisMessage notificationRedisMessage(Long recipientUserId) {
        return new NotificationRedisMessage(
                15L,
                recipientUserId,
                NotificationType.MEMBER_JOINED,
                "test2 님이 팀에 합류했습니다",
                NotificationReferenceType.TEAM,
                null,
                false,
                LocalDateTime.of(2026, 10, 9, 13, 30, 12)
        );
    }

    private void clearSent(TestSseEmitter emitter) {
        emitter.sentData = null;
        emitter.sendAttempted = false;
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
        private boolean sendAttempted;
        private Set<ResponseBodyEmitter.DataWithMediaType> sentData;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            sendAttempted = true;
            sentData = builder.build();
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
