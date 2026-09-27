package com.backend.meety.domain.transcript;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class DiarizationPolicy {

    public static final long SCHEDULER_POLL_DELAY_MILLIS = 5_000L;

    public static final int SCHEDULER_BATCH_SIZE = 5;

    public static final long MAX_RETRY_COUNT = 3L;

    /**
     * FE 오디오 업로드를 기다리는 한도. 업로드 URL 유효기간과 같다.
     */
    public static final Duration AUDIO_WAIT_LIMIT = Duration.ofMinutes(10);

    private static final String IDEMPOTENCY_KEY_PREFIX = "DIARIZATION:MEETING:";
    private static final String SPEAKER_LABEL_PREFIX = "화자 ";

    /**
     * 서버 유도 멱등키 규칙: {행위}:{원본타입}:{원본ID}. 화자 분리는 회의당 한 번뿐이다.
     */
    public static String idempotencyKey(Long meetingId) {
        return IDEMPOTENCY_KEY_PREFIX + meetingId;
    }

    // ponytail: ai_requests에 회의 참조가 없어 멱등키의 원본ID를 역파싱한다. 회의 참조 컬럼이 생기면 교체
    public static Long meetingIdOf(String idempotencyKey) {
        return Long.valueOf(idempotencyKey.substring(IDEMPOTENCY_KEY_PREFIX.length()));
    }

    /**
     * AI speakerId는 0부터 시작하므로 표시 라벨은 1부터 매긴다.
     */
    public static String speakerLabel(Long speakerId) {
        return SPEAKER_LABEL_PREFIX + (speakerId + 1);
    }
}
