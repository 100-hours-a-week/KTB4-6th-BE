package com.backend.meety.domain.ai.client;

public record MetricAiSegment(
        Long segmentId,
        Long speakerId,
        Long sequenceNumber,
        String content,
        Long startedAtMs,
        Long endedAtMs
) {
}
