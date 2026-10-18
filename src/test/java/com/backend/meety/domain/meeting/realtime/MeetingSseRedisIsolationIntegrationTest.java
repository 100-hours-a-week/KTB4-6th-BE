package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.realtime.NotificationRedisMessage;
import com.backend.meety.domain.notification.realtime.NotificationRedisSubscriber;
import com.backend.meety.domain.notification.realtime.NotificationSseConnectionIdGenerator;
import com.backend.meety.domain.notification.realtime.NotificationSseEmitterFactory;
import com.backend.meety.domain.notification.realtime.NotificationSseRegistry;
import com.backend.meety.domain.notification.realtime.NotificationSseService;
import com.backend.meety.global.config.RedisConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

@EnabledIfEnvironmentVariable(named = "MEETING_SSE_TEST_REDIS_PORT", matches = "[0-9]+")
class MeetingSseRedisIsolationIntegrationTest {

    @RepeatedTest(3)
    @DisplayName("실제 공유 Redis 리스너에서 느린 회의 전송은 다른 회의와 Notification 처리도 지연시킨다")
    void synchronousSubscriberBlocksMeetingAndNotification() throws Exception {
        // 테스트 목적:
        // 수정 전과 동일한 동기 전송을 실제 RedisConfig의 공유 컨테이너에서 실행해
        // 다른 회의와 Notification 리스너까지 지연시키는지 추정 없이 재현한다.

        // given
        try (Scenario scenario = new Scenario(false)) {
            // when
            scenario.publishMeeting(100L);
            assertThat(scenario.slowEntered.await(2, TimeUnit.SECONDS)).isTrue();
            long bPublished = System.nanoTime();
            scenario.publishMeeting(200L);
            long notificationPublished = System.nanoTime();
            scenario.publishNotification();
            scenario.releaser.schedule(scenario.releaseSlow::countDown, 500, TimeUnit.MILLISECONDS);

            // then
            assertThat(scenario.meetingBDone.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(scenario.notificationDone.await(100, TimeUnit.MILLISECONDS)).isFalse();
            assertThat(scenario.slowSubscriberReturned.getCount()).isEqualTo(1L);
            assertThat(scenario.meetingBDone.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(scenario.notificationDone.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(scenario.slowSubscriberReturned.await(2, TimeUnit.SECONDS)).isTrue();
            System.out.printf("SSE_REDIS_BASELINE subscriber_ms=%.3f meeting_b_ms=%.3f notification_ms=%.3f slow_send_ms=%.3f%n",
                    scenario.slowSubscriberNanos.get() / 1_000_000.0,
                    (scenario.meetingBSentAt.get() - bPublished) / 1_000_000.0,
                    (scenario.notificationSentAt.get() - notificationPublished) / 1_000_000.0,
                    scenario.slowSendNanos.get() / 1_000_000.0);
        }
    }

    @RepeatedTest(3)
    @DisplayName("실제 Redis에서 느린 회의의 send를 기다리지 않고 다른 회의와 Notification을 처리한다")
    void asyncSubscriberIsolatesMeetingAndNotification() throws Exception {
        // 테스트 목적:
        // 같은 Redis 컨테이너를 사용하는 실제 Subscriber가 느린 전송 전에 반환하고
        // 다른 worker의 회의 전송과 기존 Notification 경로도 진행되는지 검증한다.

        // given
        try (Scenario scenario = new Scenario(true)) {
            // when
            scenario.publishMeeting(100L);
            assertThat(scenario.slowEntered.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(scenario.slowSubscriberReturned.await(1, TimeUnit.SECONDS)).isTrue();
            long bPublished = System.nanoTime();
            scenario.publishMeeting(200L);
            long notificationPublished = System.nanoTime();
            scenario.publishNotification();
            scenario.releaser.schedule(scenario.releaseSlow::countDown, 500, TimeUnit.MILLISECONDS);

            // then
            assertThat(scenario.meetingBDone.await(200, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(scenario.notificationDone.await(200, TimeUnit.MILLISECONDS)).isTrue();
            assertThat(scenario.releaseSlow.getCount()).isEqualTo(1L);
            double activeWorkers = scenario.meters.get("meeting.sse.workers.active").gauge().value();
            double pendingEvents = scenario.meters.get("meeting.sse.pending.events").gauge().value();
            assertThat(activeWorkers).isGreaterThanOrEqualTo(1);
            scenario.awaitIdle();
            System.out.printf("SSE_REDIS_ASYNC subscriber_ms=%.3f meeting_b_ms=%.3f notification_ms=%.3f slow_send_ms=%.3f "
                            + "queue_wait_max_ms=%.3f broadcast_max_ms=%.3f send_max_ms=%.3f active_workers=%.0f pending_events=%.0f rejected=%.0f%n",
                    scenario.slowSubscriberNanos.get() / 1_000_000.0,
                    (scenario.meetingBSentAt.get() - bPublished) / 1_000_000.0,
                    (scenario.notificationSentAt.get() - notificationPublished) / 1_000_000.0,
                    scenario.slowSendNanos.get() / 1_000_000.0,
                    scenario.meters.get("meeting.sse.queue.wait").timer().max(TimeUnit.MILLISECONDS),
                    scenario.meters.get("meeting.sse.broadcast").timer().max(TimeUnit.MILLISECONDS),
                    scenario.meters.get("meeting.sse.emitter.send").timer().max(TimeUnit.MILLISECONDS),
                    activeWorkers, pendingEvents,
                    scenario.meters.find("meeting.sse.rejected").counters().stream().mapToDouble(counter -> counter.count()).sum());
            assertThat(scenario.dispatcher.meetingQueueCount()).isZero();
            assertThat(scenario.dispatcher.pendingEventCount()).isZero();
        }
    }

    private static class Scenario implements AutoCloseable {

        private final ObjectMapper mapper = new ObjectMapper();
        private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        private final ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        private final MeetingSseDispatcher dispatcher;
        private final CountDownLatch slowEntered = new CountDownLatch(1);
        private final CountDownLatch releaseSlow = new CountDownLatch(1);
        private final CountDownLatch slowSubscriberReturned = new CountDownLatch(1);
        private final CountDownLatch meetingBDone = new CountDownLatch(1);
        private final CountDownLatch notificationDone = new CountDownLatch(1);
        private final AtomicLong slowSubscriberNanos = new AtomicLong();
        private final AtomicLong slowSendNanos = new AtomicLong();
        private final AtomicLong meetingBSentAt = new AtomicLong();
        private final AtomicLong notificationSentAt = new AtomicLong();
        private final java.util.concurrent.ScheduledExecutorService releaser = Executors.newSingleThreadScheduledExecutor();
        private final LettuceConnectionFactory subscriberFactory;
        private final LettuceConnectionFactory publisherFactory;
        private final RedisMessageListenerContainer container;
        private final StringRedisTemplate publisher;

        Scenario(boolean async) {
            int port = Integer.parseInt(System.getenv("MEETING_SSE_TEST_REDIS_PORT"));
            subscriberFactory = connectionFactory(port);
            publisherFactory = connectionFactory(port);
            publisher = new StringRedisTemplate(publisherFactory);
            MeetingSseRegistry registry = new MeetingSseRegistry(meters);
            registry.register(100L, 1L, new SseEmitter() {
                @Override
                public void send(SseEventBuilder builder) throws IOException {
                    long started = System.nanoTime();
                    slowEntered.countDown();
                    try {
                        if (!releaseSlow.await(5, TimeUnit.SECONDS)) {
                            throw new IOException("slow emitter was not released");
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException(e);
                    } finally {
                        slowSendNanos.set(System.nanoTime() - started);
                    }
                }
            });
            registry.register(200L, 1L, trackingEmitter(meetingBSentAt, meetingBDone));
            executor.setCorePoolSize(2);
            executor.setMaxPoolSize(2);
            executor.setQueueCapacity(10);
            executor.setThreadNamePrefix("test-meeting-sse-");
            executor.initialize();
            dispatcher = new MeetingSseDispatcher(executor, new MeetingSseQueueProperties(100, 1000), registry, meters);
            MessageListener subscriber;
            if (async) {
                subscriber = new MeetingSseSubscriber(dispatcher, mapper, meters);
            } else {
                // Test-only control: the pre-refactor BROADCAST path on the same real Redis container.
                subscriber = (message, pattern) -> {
                    MeetingSseMessage payload = mapper.readValue(
                            new String(message.getBody(), StandardCharsets.UTF_8), MeetingSseMessage.class);
                    registry.broadcast(payload.meetingId(), payload.eventName(), payload.payload());
                };
            }
            NotificationSseRegistry notifications = new NotificationSseRegistry();
            notifications.register(1L, "test", trackingEmitter(notificationSentAt, notificationDone));
            NotificationSseService notificationService = new NotificationSseService(notifications,
                    new NotificationSseEmitterFactory(), new NotificationSseConnectionIdGenerator(), Clock.systemUTC());
            NotificationRedisSubscriber notificationSubscriber = new NotificationRedisSubscriber(mapper, notificationService);
            container = new RedisConfig().redisMessageListenerContainer(subscriberFactory);
            container.addMessageListener((message, pattern) -> {
                long started = System.nanoTime();
                MeetingSseMessage payload = mapper.readValue(
                        new String(message.getBody(), StandardCharsets.UTF_8), MeetingSseMessage.class);
                try {
                    subscriber.onMessage(message, pattern);
                } finally {
                    if (payload.meetingId().equals(100L)) {
                        slowSubscriberNanos.set(System.nanoTime() - started);
                        slowSubscriberReturned.countDown();
                    }
                }
            }, new ChannelTopic("meeting-sse"));
            container.addMessageListener(notificationSubscriber, new ChannelTopic("notification-sse"));
            container.afterPropertiesSet();
            container.start();
        }

        void publishMeeting(long meetingId) {
            MeetingSseMessage message = new MeetingSseMessage(MeetingSseCommand.BROADCAST, meetingId, null,
                    "TRANSCRIPT_CREATED", mapper.createObjectNode().put("sequence", 101));
            assertThat(publisher.convertAndSend("meeting-sse", mapper.writeValueAsString(message))).isGreaterThanOrEqualTo(1L);
        }

        void publishNotification() {
            NotificationRedisMessage message = new NotificationRedisMessage(1L, 1L, NotificationType.MEETING_STARTED,
                    "회의 시작", NotificationReferenceType.MEETING, 100L, false, LocalDateTime.now());
            assertThat(publisher.convertAndSend("notification-sse", mapper.writeValueAsString(message))).isGreaterThanOrEqualTo(1L);
        }

        private static SseEmitter trackingEmitter(AtomicLong sentAt, CountDownLatch done) {
            return new SseEmitter() {
                @Override
                public void send(SseEventBuilder builder) {
                    sentAt.set(System.nanoTime());
                    done.countDown();
                }
            };
        }

        private static LettuceConnectionFactory connectionFactory(int port) {
            LettuceConnectionFactory factory = new LettuceConnectionFactory("127.0.0.1", port);
            factory.afterPropertiesSet();
            factory.start();
            return factory;
        }

        void awaitIdle() {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (dispatcher.meetingQueueCount() != 0 && System.nanoTime() < deadline) {
                java.util.concurrent.locks.LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
            }
            assertThat(dispatcher.meetingQueueCount()).isZero();
        }

        @Override
        public void close() throws Exception {
            releaseSlow.countDown();
            releaser.shutdownNow();
            container.stop();
            container.destroy();
            dispatcher.stopAccepting();
            executor.destroy();
            subscriberFactory.destroy();
            publisherFactory.destroy();
            meters.close();
        }
    }
}
