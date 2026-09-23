package com.backend.meety.domain.transcript.realtime;

import java.time.LocalDateTime;

public record TranscriptSegmentSseResponse(
        Long id,
        Long sequenceNumber,
        String content,
        Long startedAtMs,
        Long endedAtMs,
        LocalDateTime recognizedAt,
        Object speaker
) {
}
