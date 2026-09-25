package com.backend.meety.domain.ai.client;

public record SummaryAiSegment(
        Long segmentId,
        Long speakerId,
        Long sequenceNumber,
        String content,
        Long startedAtMs,
        Long endedAtMs
) {
}
