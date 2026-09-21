package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.recording.service.RecordingService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingTimeoutScheduler {

    private static final Duration MAX_RECORDING_DURATION = Duration.ofMinutes(90);
    private static final Duration MAX_PAUSE_DURATION = Duration.ofMinutes(30);

    private final TaskScheduler taskScheduler;
    private final Clock clock;
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> maxDurationTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Long, ScheduledFuture<?>> pauseTasks = new ConcurrentHashMap<>();
    private final RecordingService recordingService;

    public void scheduleMaxDurationTimeout(Long recordingSessionId) {
        cancel(maxDurationTasks.remove(recordingSessionId));
        ScheduledFuture<?> task = taskScheduler.schedule(
                () -> completeByTimeout(recordingSessionId),
                Instant.now(clock).plus(MAX_RECORDING_DURATION)
        );
        maxDurationTasks.put(recordingSessionId, task);
    }

    public void schedulePauseTimeout(Long recordingSessionId) {
        cancelPauseTimeout(recordingSessionId);
        ScheduledFuture<?> task = taskScheduler.schedule(
                () -> completeByTimeout(recordingSessionId),
                Instant.now(clock).plus(MAX_PAUSE_DURATION)
        );
        pauseTasks.put(recordingSessionId, task);
    }

    public void cancelPauseTimeout(Long recordingSessionId) {
        cancel(pauseTasks.remove(recordingSessionId));
    }

    public void cancelAll(Long recordingSessionId) {
        cancel(maxDurationTasks.remove(recordingSessionId));
        cancel(pauseTasks.remove(recordingSessionId));
    }

    private void completeByTimeout(Long recordingSessionId) {
        try {
            recordingService.completeByTimeout(recordingSessionId);
        } catch (RuntimeException e) {
            log.warn("녹음 timeout 자동 종료에 실패했습니다. recordingSessionId={}", recordingSessionId, e);
        }
    }

    private void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }
}
