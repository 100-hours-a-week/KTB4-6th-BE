package com.backend.meety.domain.transcript.realtime;

import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentMessage;
import com.backend.meety.domain.transcript.service.TranscriptService;
import com.backend.meety.global.config.AsyncConfig;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Slf4j
@Component
public class TranscriptSaveDispatcher {

    private final Object monitor = new Object();
    private final Map<Long, RecordingQueue> queues = new HashMap<>();
    private final Executor executor;
    private final TranscriptSaveProperties properties;
    private final TranscriptService transcriptService;
    private int pendingTasks;
    private boolean closed;

    public TranscriptSaveDispatcher(
            @Qualifier(AsyncConfig.TRANSCRIPT_SAVE_TASK_EXECUTOR_BEAN_NAME) Executor executor,
            TranscriptSaveProperties properties,
            TranscriptService transcriptService
    ) {
        this.executor = executor;
        this.properties = properties;
        this.transcriptService = transcriptService;
    }

    public boolean submitSave(AiTranscriptSegmentMessage message) {
        String rejection = enqueue(message.recordingSessionId(), () -> runWithRetry("전사 저장", message.recordingSessionId(),
                message.sourceSegmentKey(), () -> transcriptService.saveFinalSegment(message)));
        if (rejection == null) {
            return true;
        }
        log.error("전사 저장 요청을 거절해 전사가 유실됩니다. reason={}, recordingSessionId={}, sourceSegmentKey={}",
                rejection, message.recordingSessionId(), message.sourceSegmentKey());
        return false;
    }

    public void submitFinalize(Long recordingSessionId, Runnable finalizer) {
        String rejection = enqueue(recordingSessionId,
                () -> runWithRetry("전사 확정", recordingSessionId, null, finalizer));
        if (rejection == null) {
            return;
        }
        log.warn("전사 확정 작업이 큐에 들어가지 못해 남은 저장을 기다리지 않고 실행합니다. reason={}, recordingSessionId={}",
                rejection, recordingSessionId);
        finalizer.run();
    }

    private String enqueue(Long recordingSessionId, Runnable action) {
        synchronized (monitor) {
            if (closed) {
                return "closed";
            }
            RecordingQueue queue = queues.get(recordingSessionId);
            if (queue != null && queue.tasks.size() >= properties.maxPendingPerRecording()) {
                return "recording-capacity";
            }
            if (pendingTasks >= properties.maxPendingTotal()) {
                return "total-capacity";
            }
            if (queue != null) {
                queue.tasks.addLast(action);
                pendingTasks++;
                return null;
            }
            RecordingQueue created = new RecordingQueue(recordingSessionId);
            created.tasks.addLast(action);
            pendingTasks++;
            queues.put(recordingSessionId, created);
            try {
                executor.execute(() -> drain(created));
                return null;
            } catch (RejectedExecutionException e) {
                queues.remove(recordingSessionId, created);
                pendingTasks--;
                return "executor";
            }
        }
    }

    private void drain(RecordingQueue queue) {
        while (true) {
            Runnable task;
            synchronized (monitor) {
                task = queue.tasks.pollFirst();
                if (task == null) {
                    queues.remove(queue.recordingSessionId, queue);
                    return;
                }
                pendingTasks--;
            }
            try {
                task.run();
            } catch (RuntimeException e) {
                log.error("전사 저장 큐 작업이 실패했습니다. recordingSessionId={}", queue.recordingSessionId, e);
            }
        }
    }

    private void runWithRetry(String taskName, Long recordingSessionId, String sourceSegmentKey, Runnable action) {
        long startedAt = System.nanoTime();
        long deadline = startedAt + properties.retryMaxDuration().toNanos();
        Duration backoff = properties.retryInitialBackoff();
        int attempt = 1;
        while (true) {
            try {
                action.run();
                if (attempt > 1) {
                    log.info("{} 재시도 성공. recordingSessionId={}, sourceSegmentKey={}, attempts={}, elapsedMs={}",
                            taskName, recordingSessionId, sourceSegmentKey, attempt,
                            TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt));
                }
                return;
            } catch (RuntimeException e) {
                if (!isRetryable(e)) {
                    log.error("{}에 실패했습니다. 재시도하지 않습니다. recordingSessionId={}, sourceSegmentKey={}",
                            taskName, recordingSessionId, sourceSegmentKey, e);
                    return;
                }
                long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0) {
                    log.error("{} 재시도 한도를 넘겨 포기합니다. recordingSessionId={}, sourceSegmentKey={}, attempts={}",
                            taskName, recordingSessionId, sourceSegmentKey, attempt, e);
                    return;
                }
                log.warn("{}에 실패해 재시도합니다. recordingSessionId={}, sourceSegmentKey={}, attempt={}, cause={}",
                        taskName, recordingSessionId, sourceSegmentKey, attempt, e.toString());
                if (!sleep(Math.min(backoff.toNanos(), remainingNanos))) {
                    log.error("{} 재시도가 중단돼 포기합니다. recordingSessionId={}, sourceSegmentKey={}",
                            taskName, recordingSessionId, sourceSegmentKey);
                    return;
                }
                Duration doubled = backoff.multipliedBy(2);
                backoff = doubled.compareTo(properties.retryMaxBackoff()) > 0 ? properties.retryMaxBackoff() : doubled;
                attempt++;
            }
        }
    }

    private boolean isRetryable(RuntimeException e) {
        if (e instanceof DataIntegrityViolationException) {
            return false;
        }
        return e instanceof DataAccessException || e instanceof TransactionException;
    }

    private boolean sleep(long nanos) {
        try {
            TimeUnit.NANOSECONDS.sleep(nanos);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    int pendingTaskCount() {
        synchronized (monitor) {
            return pendingTasks;
        }
    }

    int recordingQueueCount() {
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

    private static class RecordingQueue {
        private final Long recordingSessionId;
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();

        RecordingQueue(Long recordingSessionId) {
            this.recordingSessionId = recordingSessionId;
        }
    }
}
