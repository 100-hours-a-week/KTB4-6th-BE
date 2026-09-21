package com.backend.meety.domain.transcript.event;

import java.time.LocalDateTime;

public record TranscriptCreatedEvent(
        Long meetingId,
        Long recordingSessionId,
        Long transcriptSegmentId,
        Long sequenceNumber,
        String content,
        Long startedAtMs,
        Long endedAtMs,
        LocalDateTime recognizedAt
) {
}
