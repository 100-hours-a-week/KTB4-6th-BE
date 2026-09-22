package com.backend.meety.domain.ai.realtime;

import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

@Component
public class DefaultAiStopTimeoutScheduler implements AiStopTimeoutScheduler {

    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "ai-stop-timeout");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public ScheduledFuture<?> schedule(Runnable task, Duration delay) {
        return executor.schedule(task, delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    @PreDestroy
    public void shutdown() {
        executor.shutdownNow();
    }
}
