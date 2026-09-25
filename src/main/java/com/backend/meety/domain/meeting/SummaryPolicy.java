package com.backend.meety.domain.meeting;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class SummaryPolicy {

    public static final long SCHEDULER_POLL_DELAY_MILLIS = 5_000L;

    public static final int SCHEDULER_BATCH_SIZE = 5;

    public static final long MAX_RETRY_COUNT = 3L;

    /**
     * 서버 유도 멱등키 규칙: {행위}:{원본타입}:{원본ID}. 원장 키와 동일한 3부 형식이다.
     * 최초 요약은 회의당 한 번뿐이라 회차가 없다. 수동 재생성은 클라이언트 UUID를 그대로 쓴다.
     */
    public static String firstSummaryIdempotencyKey(Long meetingId) {
        return "SUMMARY:MEETING:" + meetingId;
    }
}
