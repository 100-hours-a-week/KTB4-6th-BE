package com.backend.meety.domain.ai.client;

public record ChatAiTranscriptSegment(
        Long segmentId,
        String speakerDisplayName,
        Long sequenceNumber,
        String content,
        Long startedAtMs,
        Long endedAtMs
) {
}
