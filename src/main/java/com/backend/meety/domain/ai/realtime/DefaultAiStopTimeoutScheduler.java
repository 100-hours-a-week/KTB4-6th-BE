package com.backend.meety.domain.ai.realtime;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class DefaultAiStopTimeoutScheduler implements AiStopTimeoutScheduler {

    private final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, runnable -> {
        Thread thread = new Thread(runnable, "ai-stop-timeout");
        thread.setDaemon(true);
        return thread;
    });

    public DefaultAiStopTimeoutScheduler() {
        executor.setRemoveOnCancelPolicy(true);
    }

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        return executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
