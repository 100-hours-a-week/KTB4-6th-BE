package com.backend.meety.domain.ai.client;

public record SummaryAiSegment(
        Long segmentId,
        Long speakerId,
        String displayName,
        Long sequenceNumber,
        String content,
        Long startedAtMs,
        Long endedAtMs
) {
}
