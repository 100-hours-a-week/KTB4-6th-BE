package com.backend.meety.domain.meeting.realtime;

import com.backend.meety.global.config.AsyncConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PreDestroy;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MeetingSseDispatcher {

    private final Object monitor = new Object();
    private final Map<Long, MeetingQueue> queues = new HashMap<>();
    private final ThreadPoolTaskExecutor executor;
    private final MeetingSseQueueProperties properties;
    private final MeetingSseRegistry registry;
    private final Timer queueWait;
    private final Timer broadcastDuration;
    private final Counter commandFailures;
    private final Map<String, Counter> rejections;
    private int pendingEvents;
    private boolean closed;

    public MeetingSseDispatcher(
            @Qualifier(AsyncConfig.MEETING_SSE_TASK_EXECUTOR_BEAN_NAME) ThreadPoolTaskExecutor executor,
            MeetingSseQueueProperties properties,
            MeetingSseRegistry registry,
            MeterRegistry meters
    ) {
        this.executor = executor;
        this.properties = properties;
        this.registry = registry;
        queueWait = meters.timer("meeting.sse.queue.wait");
        broadcastDuration = meters.timer("meeting.sse.broadcast");
        commandFailures = meters.counter("meeting.sse.command.failures");
        rejections = Map.of(
                "meeting-capacity", meters.counter("meeting.sse.rejected", "reason", "meeting-capacity"),
                "total-capacity", meters.counter("meeting.sse.rejected", "reason", "total-capacity"),
                "executor", meters.counter("meeting.sse.rejected", "reason", "executor"),
                "closed", meters.counter("meeting.sse.rejected", "reason", "closed"),
                "invalid", meters.counter("meeting.sse.rejected", "reason", "invalid"));
        Gauge.builder("meeting.sse.pending.events", this, MeetingSseDispatcher::pendingEventCount).register(meters);
        Gauge.builder("meeting.sse.queues", this, MeetingSseDispatcher::meetingQueueCount).register(meters);
        Gauge.builder("meeting.sse.workers.active", executor, ThreadPoolTaskExecutor::getActiveCount).register(meters);
        Gauge.builder("meeting.sse.executor.queued.tasks", executor,
                pool -> pool.getThreadPoolExecutor().getQueue().size()).register(meters);
    }

    public boolean dispatch(MeetingSseMessage message) {
        if (message == null || message.meetingId() == null || message.command() == null) {
            return reject("invalid", message);
        }
        synchronized (monitor) {
            if (closed) {
                return reject("closed", message);
            }
            MeetingQueue queue = queues.get(message.meetingId());
            if (queue != null && queue.events.size() >= properties.maxPendingPerMeeting()) {
                return reject("meeting-capacity", message);
            }
            if (pendingEvents >= properties.maxPendingTotal()) {
                return reject("total-capacity", message);
            }
            if (queue != null) {
                queue.events.addLast(new PendingEvent(message, System.nanoTime()));
                pendingEvents++;
                return true;
            }
            MeetingQueue created = new MeetingQueue(message.meetingId());
            created.events.addLast(new PendingEvent(message, System.nanoTime()));
            pendingEvents++;
            queues.put(message.meetingId(), created);
            try {
                executor.execute(() -> drain(created));
                return true;
            } catch (RejectedExecutionException e) {
                queues.remove(message.meetingId(), created);
                pendingEvents--;
                return reject("executor", message);
            }
        }
    }

    private void drain(MeetingQueue queue) {
        while (true) {
            PendingEvent event;
            synchronized (monitor) {
                event = queue.events.pollFirst();
                if (event == null) {
                    queues.remove(queue.meetingId, queue);
                    return;
                }
                pendingEvents--;
            }
            queueWait.record(System.nanoTime() - event.enqueuedAt(), TimeUnit.NANOSECONDS);
            try {
                handle(event.message());
            } catch (RuntimeException e) {
                commandFailures.increment();
                log.error("회의 SSE 명령 실행에 실패했습니다. meetingId={}, command={}",
                        event.message().meetingId(), event.message().command(), e);
            }
        }
    }

    private void handle(MeetingSseMessage message) {
        switch (message.command()) {
            case BROADCAST -> broadcastDuration.record(() -> {
                int count = registry.broadcast(message.meetingId(), message.eventName(), message.payload());
                log.debug("회의 SSE 전송 완료. meetingId={}, event={}, emitterCount={}",
                        message.meetingId(), message.eventName(), count);
            });
            case COMPLETE_PARTICIPANT -> registry.complete(message.meetingId(), message.userId());
            case COMPLETE_ALL -> registry.completeAll(message.meetingId());
            case SEND_AND_COMPLETE_ALL -> broadcastDuration.record(() ->
                    registry.sendAndCompleteAll(message.meetingId(), message.eventName(), message.payload()));
        }
    }

    private boolean reject(String reason, MeetingSseMessage message) {
        rejections.get(reason).increment();
        log.warn("회의 SSE 이벤트를 거절했습니다. reason={}, meetingId={}, command={}, eventName={}", reason,
                message == null ? null : message.meetingId(), message == null ? null : message.command(),
                message == null ? null : message.eventName());
        return false;
    }

    int pendingEventCount() {
        synchronized (monitor) {
            return pendingEvents;
        }
    }

    int meetingQueueCount() {
        synchronized (monitor) {
            return queues.size();
        }
    }

    @PreDestroy
    void stopAccepting() {
        synchronized (monitor) {
            closed = true;
        }
    }

    private static class MeetingQueue {
        private final Long meetingId;
        private final ArrayDeque<PendingEvent> events = new ArrayDeque<>();

        MeetingQueue(Long meetingId) {
            this.meetingId = meetingId;
        }
    }

    private record PendingEvent(MeetingSseMessage message, long enqueuedAt) {
    }
}
