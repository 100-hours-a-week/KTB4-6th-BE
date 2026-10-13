package com.backend.meety.domain.meeting;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class MetricPolicy {

    public static final long SCHEDULER_POLL_DELAY_MILLIS = 5_000L;

    public static final int SCHEDULER_BATCH_SIZE = 5;

    public static final long MAX_RETRY_COUNT = 3L;

    private static final String IDEMPOTENCY_KEY_PREFIX = "METRIC:SUMMARY:";

    /**
     * 서버 유도 멱등키 규칙: {행위}:{원본타입}:{원본ID}. Metric은 요약 버전별로 하나씩 등록한다.
     */
    public static String idempotencyKey(Long summaryId) {
        return IDEMPOTENCY_KEY_PREFIX + summaryId;
    }

    public static Long summaryIdOf(String idempotencyKey) {
        return Long.valueOf(idempotencyKey.substring(IDEMPOTENCY_KEY_PREFIX.length()));
    }
}
