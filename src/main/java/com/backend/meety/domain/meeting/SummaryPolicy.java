package com.backend.meety.domain.meeting;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SummaryPolicy {

    public static final long SCHEDULER_POLL_DELAY_MILLIS = 5_000L;

    public static final int SCHEDULER_BATCH_SIZE = 5;

    public static final long MAX_RETRY_COUNT = 3L;
}
