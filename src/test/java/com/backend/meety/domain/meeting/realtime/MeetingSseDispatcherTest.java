package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.DefaultMessage;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

class MeetingSseDispatcherTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private SimpleMeterRegistry meters;
    private ThreadPoolTaskExecutor workers;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        workers = workers(2, 16);
    }

    @AfterEach
    void tearDown() throws Exception {
        workers.shutdown();
        workers.getThreadPoolExecutor().awaitTermination(3, TimeUnit.SECONDS);
        meters.close();
    }

    @Test
    @DisplayName("같은 회의의 전사·상태·참가자 종료·전체 종료 명령은 제출 순서를 유지한다")
    void ordersAllCommandsWithinMeeting() throws Exception {
        // 테스트 목적:
        // 같은 회의의 첫 전송이 막혀 있어도 후속 상태·전사·연결 종료 명령이
        // 앞선 전송을 추월하지 않고 제출 순서대로 실행되는지 검증한다.

        // given
        MeetingSseRegistry registry = mock(MeetingSseRegistry.class);
        List<String> order = new CopyOnWriteArrayList<>();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            String name = call.getArgument(1);
            order.add(name);
            if (name.equals("101")) {
                entered.countDown();
                await(release);
            }
            return 1;
        }).when(registry).broadcast(anyLong(), anyString(), any());
        doAnswer(call -> { order.add("COMPLETE_PARTICIPANT"); return null; })
                .when(registry).complete(100L, 1L);
        doAnswer(call -> { order.add("SEND_AND_COMPLETE_ALL"); return null; })
                .when(registry).sendAndCompleteAll(anyLong(), anyString(), any());
        doAnswer(call -> { order.add("COMPLETE_ALL"); return null; }).when(registry).completeAll(100L);
        MeetingSseDispatcher dispatcher = dispatcher(registry, 100, 1000);

        // when
        try {
            assertThat(dispatcher.dispatch(message(100L, "101"))).isTrue();
            await(entered);
            dispatcher.dispatch(message(100L, "PAUSE"));
            dispatcher.dispatch(message(100L, "102"));
            dispatcher.dispatch(command(MeetingSseCommand.COMPLETE_PARTICIPANT));
            dispatcher.dispatch(command(MeetingSseCommand.SEND_AND_COMPLETE_ALL));
            dispatcher.dispatch(command(MeetingSseCommand.COMPLETE_ALL));

            // then
            assertThat(order).containsExactly("101");
            assertThat(dispatcher.pendingEventCount()).isEqualTo(5);
            release.countDown();
            awaitIdle(dispatcher);
            assertThat(order).containsExactly("101", "PAUSE", "102", "COMPLETE_PARTICIPANT",
                    "SEND_AND_COMPLETE_ALL", "COMPLETE_ALL");
            assertThat(meters.get("meeting.sse.queue.wait").timer().count()).isEqualTo(6);
            assertThat(meters.get("meeting.sse.broadcast").timer().count()).isEqualTo(4);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("느린 회의가 실행 중이어도 Subscriber는 반환하고 다른 회의는 다른 worker에서 진행한다")
    void subscriberReturnsAndOtherMeetingRunsBeforeSlowSendFinishes() throws Exception {
        // 테스트 목적:
        // Redis Subscriber 호출자가 느린 전송 완료를 기다리지 않고 반환하며
        // 다른 회의는 사용 가능한 worker에서 전송되는지 검증한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(meters);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherSent = new CountDownLatch(1);
        registry.register(100L, 1L, blockingEmitter(entered, release));
        registry.register(200L, 1L, trackingEmitter(otherSent));
        MeetingSseDispatcher dispatcher = dispatcher(registry, 100, 1000);
        MeetingSseSubscriber subscriber = new MeetingSseSubscriber(dispatcher, mapper, meters);
        var caller = Executors.newSingleThreadExecutor();

        // when
        try {
            var returned = caller.submit(() -> subscriber.onMessage(redisMessage(message(100L, "101")), null));
            await(entered);
            returned.get(1, TimeUnit.SECONDS);
            dispatcher.dispatch(message(200L, "201"));

            // then
            await(otherSent);
            assertThat(release.getCount()).isEqualTo(1L);
            assertThat(meters.get("meeting.sse.subscriber").timer().count()).isEqualTo(1L);
            assertThat(meters.get("meeting.sse.workers.active").gauge().value()).isGreaterThanOrEqualTo(1);
            release.countDown();
            awaitIdle(dispatcher);
            assertThat(meters.get("meeting.sse.emitter.send").timer().count()).isEqualTo(2);
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    @DisplayName("COMPLETE_ALL은 앞선 실제 emitter 전송이 끝나기 전에 연결을 제거하지 않는다")
    void completeCannotOvertakeActualBroadcast() throws Exception {
        // 테스트 목적:
        // 느린 emitter의 전송이 진행되는 동안 같은 회의의 종료 명령이 대기하고
        // 전송 이후에만 연결을 종료·정리하는지 검증한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(meters);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        registry.register(100L, 1L, blockingEmitter(entered, release));
        MeetingSseDispatcher dispatcher = dispatcher(registry, 100, 1000);

        // when
        try {
            dispatcher.dispatch(message(100L, "101"));
            await(entered);
            dispatcher.dispatch(command(MeetingSseCommand.COMPLETE_ALL));

            // then
            assertThat(registry.count(100L)).isEqualTo(1);
            release.countDown();
            awaitIdle(dispatcher);
            assertThat(registry.count(100L)).isZero();
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("회의별 pending 상한 초과는 새 명령만 거절하고 기존 명령의 순서를 보존한다")
    void meetingCapacityRejectsNewestWithoutInlineSend() throws Exception {
        // 테스트 목적:
        // 같은 회의의 대기열이 가득 차면 새 명령이 worker 밖에서 실행되지 않고
        // 이미 수락한 명령만 원래 순서로 실행되는지 검증한다.

        // given
        MeetingSseRegistry registry = mock(MeetingSseRegistry.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        List<String> sent = new CopyOnWriteArrayList<>();
        doAnswer(call -> {
            sent.add(call.getArgument(1));
            entered.countDown();
            await(release);
            assertThat(Thread.currentThread().getName()).startsWith("test-meeting-sse-");
            return 1;
        }).when(registry).broadcast(anyLong(), anyString(), any());
        MeetingSseDispatcher dispatcher = dispatcher(registry, 1, 10);

        // when
        try {
            dispatcher.dispatch(message(100L, "101"));
            await(entered);
            assertThat(dispatcher.dispatch(message(100L, "102"))).isTrue();
            boolean accepted = dispatcher.dispatch(command(MeetingSseCommand.COMPLETE_ALL));

            // then
            assertThat(accepted).isFalse();
            assertThat(dispatcher.pendingEventCount()).isEqualTo(1);
            assertThat(sent).containsExactly("101");
            assertRejections("meeting-capacity", 1);
            release.countDown();
            awaitIdle(dispatcher);
            assertThat(sent).containsExactly("101", "102");
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("서버 전체 pending 상한 초과는 다른 회의의 새 이벤트도 즉시 거절한다")
    void totalCapacityIsBoundedAcrossMeetings() throws Exception {
        // 테스트 목적:
        // 회의별 한도에 여유가 있어도 서버 전체 pending 수가 상한이면
        // 새로운 회의의 이벤트를 수락하거나 Redis 스레드에서 실행하지 않는지 검증한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(meters);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch otherSent = new CountDownLatch(1);
        registry.register(100L, 1L, blockingEmitter(entered, release));
        registry.register(200L, 1L, trackingEmitter(otherSent));
        MeetingSseDispatcher dispatcher = dispatcher(registry, 1, 1);

        // when
        try {
            dispatcher.dispatch(message(100L, "101"));
            await(entered);
            dispatcher.dispatch(message(100L, "102"));
            boolean accepted = dispatcher.dispatch(message(200L, "201"));

            // then
            assertThat(accepted).isFalse();
            assertThat(otherSent.getCount()).isEqualTo(1L);
            assertThat(dispatcher.pendingEventCount()).isEqualTo(1);
            assertThat(dispatcher.meetingQueueCount()).isEqualTo(1);
            assertRejections("total-capacity", 1);
            release.countDown();
            awaitIdle(dispatcher);
            assertThat(dispatcher.dispatch(message(200L, "202"))).isTrue();
            await(otherSent);
            awaitIdle(dispatcher);
        } finally {
            release.countDown();
        }
    }

    @Test
    @DisplayName("Executor 포화로 거절되면 pending과 queue를 되돌리고 이후 재시도가 정상 진행된다")
    void executorRejectionRollsBackQueueWithoutCallerRuns() throws Exception {
        // 테스트 목적:
        // worker와 Executor 대기열이 가득 차면 새 회의 이벤트가 즉시 거절되며
        // queue나 pending 누수 없이 여유가 생긴 뒤 같은 회의를 다시 처리할 수 있는지 검증한다.

        // given
        workers.destroy();
        workers = workers(1, 1);
        MeetingSseRegistry registry = new MeetingSseRegistry(meters);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch queuedSent = new CountDownLatch(1);
        CountDownLatch rejectedSent = new CountDownLatch(1);
        registry.register(100L, 1L, blockingEmitter(entered, release));
        registry.register(200L, 1L, trackingEmitter(queuedSent));
        registry.register(300L, 1L, trackingEmitter(rejectedSent));
        MeetingSseDispatcher dispatcher = dispatcher(registry, 100, 1000);

        // when
        try {
            dispatcher.dispatch(message(100L, "101"));
            await(entered);
            assertThat(dispatcher.dispatch(message(200L, "201"))).isTrue();
            boolean accepted = dispatcher.dispatch(message(300L, "301"));

            // then
            assertThat(accepted).isFalse();
            assertThat(rejectedSent.getCount()).isEqualTo(1L);
            assertThat(dispatcher.pendingEventCount()).isEqualTo(1);
            assertThat(dispatcher.meetingQueueCount()).isEqualTo(2);
            assertRejections("executor", 1);
            release.countDown();
            await(queuedSent);
            awaitIdle(dispatcher);
            awaitCondition(() -> workers.getActiveCount() == 0);
            assertThat(dispatcher.dispatch(message(300L, "302"))).isTrue();
            await(rejectedSent);
            awaitIdle(dispatcher);
        } finally {
            release.countDown();
        }
    }

    @RepeatedTest(5)
    @DisplayName("동시 enqueue와 drain·cleanup 경쟁에서도 같은 회의를 중복 실행하거나 이벤트를 유실하지 않는다")
    void concurrentEnqueueDrainAndCleanupDoesNotLoseEvents() throws Exception {
        // 테스트 목적:
        // 여러 생산자가 같은 회의에 제출하는 동안 queue 생성·삭제가 반복되어도
        // 모든 이벤트를 한 번씩 처리하고 각 생산자의 순서와 단일 실행을 유지하는지 검증한다.

        // given
        MeetingSseRegistry registry = mock(MeetingSseRegistry.class);
        List<String> sent = new CopyOnWriteArrayList<>();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximum = new AtomicInteger();
        doAnswer(call -> {
            maximum.accumulateAndGet(active.incrementAndGet(), Math::max);
            try {
                sent.add(call.getArgument(1));
            } finally {
                active.decrementAndGet();
            }
            return 1;
        }).when(registry).broadcast(anyLong(), anyString(), any());
        MeetingSseDispatcher dispatcher = dispatcher(registry, 1000, 2000);
        var producers = Executors.newFixedThreadPool(4);
        CountDownLatch start = new CountDownLatch(1);

        // when
        try {
            List<java.util.concurrent.Future<?>> submissions = new ArrayList<>();
            for (int producer = 0; producer < 4; producer++) {
                int id = producer;
                submissions.add(producers.submit(() -> {
                    await(start);
                    for (int sequence = 0; sequence < 100; sequence++) {
                        assertThat(dispatcher.dispatch(message(100L, id + ":" + sequence))).isTrue();
                        if (sequence % 5 == 0) {
                            Thread.yield();
                        }
                    }
                }));
            }
            start.countDown();
            for (var submission : submissions) {
                submission.get(3, TimeUnit.SECONDS);
            }
            awaitIdle(dispatcher);

            // then
            assertThat(sent).hasSize(400).doesNotHaveDuplicates();
            assertThat(maximum).hasValue(1);
            for (int producer = 0; producer < 4; producer++) {
                String prefix = producer + ":";
                assertThat(sent.stream().filter(value -> value.startsWith(prefix))
                        .map(value -> Integer.parseInt(value.substring(prefix.length()))).toList())
                        .hasSize(100).isSorted();
            }
            assertThat(dispatcher.pendingEventCount()).isZero();
            assertThat(dispatcher.meetingQueueCount()).isZero();
            for (int cycle = 0; cycle < 100; cycle++) {
                assertThat(dispatcher.dispatch(message(100L, "idle-cycle-" + cycle))).isTrue();
                awaitIdle(dispatcher);
            }
            assertThat(sent).hasSize(500);
        } finally {
            producers.shutdownNow();
        }
    }

    @Test
    @DisplayName("한 명령이 실패해도 같은 회의의 다음 명령과 queue cleanup은 진행한다")
    void commandFailureDoesNotWedgeQueue() {
        // 테스트 목적:
        // 예상하지 못한 명령 실행 예외가 발생해도 큐가 멈추지 않고
        // 이후 명령을 처리한 뒤 정리되며 실패 지표가 증가하는지 검증한다.

        // given
        MeetingSseRegistry registry = mock(MeetingSseRegistry.class);
        AtomicInteger calls = new AtomicInteger();
        doAnswer(call -> {
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("test failure");
            }
            return 1;
        }).when(registry).broadcast(anyLong(), anyString(), any());
        MeetingSseDispatcher dispatcher = dispatcher(registry, 100, 1000);

        // when
        dispatcher.dispatch(message(100L, "101"));
        dispatcher.dispatch(message(100L, "102"));
        awaitIdle(dispatcher);

        // then
        assertThat(calls).hasValue(2);
        assertThat(meters.get("meeting.sse.command.failures").counter().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("shutdown과 잘못된 메시지는 이벤트를 실행하거나 queue를 남기지 않는다")
    void closedAndInvalidMessagesAreRejected() {
        // 테스트 목적:
        // 종료 중인 Dispatcher와 잘못된 메시지 요청이 작업을 실행하지 않고
        // 거절 사유만 지표에 남기는지 검증한다.

        // given
        MeetingSseRegistry registry = new MeetingSseRegistry(meters);
        MeetingSseDispatcher dispatcher = dispatcher(registry, 100, 1000);

        // when
        assertThat(dispatcher.dispatch(null)).isFalse();
        assertThat(dispatcher.dispatch(new MeetingSseMessage(null, 100L, null, null, null))).isFalse();
        dispatcher.stopAccepting();
        assertThat(dispatcher.dispatch(message(100L, "101"))).isFalse();

        // then
        assertRejections("invalid", 2);
        assertRejections("closed", 1);
        assertThat(dispatcher.pendingEventCount()).isZero();
        assertThat(dispatcher.meetingQueueCount()).isZero();
    }

    private MeetingSseDispatcher dispatcher(MeetingSseRegistry registry, int perMeeting, int total) {
        return new MeetingSseDispatcher(workers, new MeetingSseQueueProperties(perMeeting, total), registry, meters);
    }

    private ThreadPoolTaskExecutor workers(int size, int capacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(size);
        executor.setMaxPoolSize(size);
        executor.setQueueCapacity(capacity);
        executor.setThreadNamePrefix("test-meeting-sse-");
        executor.initialize();
        return executor;
    }

    private MeetingSseMessage message(long meetingId, String eventName) {
        return new MeetingSseMessage(MeetingSseCommand.BROADCAST, meetingId, null, eventName, mapper.createObjectNode());
    }

    private MeetingSseMessage command(MeetingSseCommand command) {
        return new MeetingSseMessage(command, 100L, 1L, "MEETING_DELETED", mapper.createObjectNode());
    }

    private DefaultMessage redisMessage(MeetingSseMessage message) {
        return new DefaultMessage("meeting-sse".getBytes(StandardCharsets.UTF_8),
                mapper.writeValueAsString(message).getBytes(StandardCharsets.UTF_8));
    }

    private static SseEmitter blockingEmitter(CountDownLatch entered, CountDownLatch release) {
        return new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                entered.countDown();
                await(release);
            }
        };
    }

    private static SseEmitter trackingEmitter(CountDownLatch done) {
        return new SseEmitter() {
            @Override
            public void send(SseEventBuilder builder) {
                assertThat(Thread.currentThread().getName()).startsWith("test-meeting-sse-");
                done.countDown();
            }
        };
    }

    private void assertRejections(String reason, long count) {
        assertThat(meters.get("meeting.sse.rejected").tag("reason", reason).counter().count()).isEqualTo(count);
    }

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static void awaitCondition(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void awaitIdle(MeetingSseDispatcher dispatcher) {
        awaitCondition(() -> dispatcher.meetingQueueCount() == 0);
        assertThat(dispatcher.pendingEventCount()).isZero();
    }
}
