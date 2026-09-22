package com.backend.meety.domain.ai.realtime;

import java.time.Duration;
import java.util.concurrent.ScheduledFuture;

public interface AiStopTimeoutScheduler {

    ScheduledFuture<?> schedule(Runnable task, Duration delay);
}
