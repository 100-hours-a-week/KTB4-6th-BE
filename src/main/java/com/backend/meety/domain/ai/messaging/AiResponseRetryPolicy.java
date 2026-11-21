package com.backend.meety.domain.ai.messaging;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AiResponseRetryPolicy {

    public static final long MAX_RETRY_COUNT = 3L;

    public static final long LAST_RETRY_COUNT = MAX_RETRY_COUNT - 1;

    private static final Duration INITIAL_BACKOFF = Duration.ofSeconds(30);

    private static final long BACKOFF_MULTIPLIER = 4L;

    public static Duration backoff(long retryCount) {
        return INITIAL_BACKOFF.multipliedBy((long) Math.pow(BACKOFF_MULTIPLIER, retryCount));
    }
}
