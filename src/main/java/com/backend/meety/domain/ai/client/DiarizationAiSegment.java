package com.backend.meety.domain.ai.client;

public record DiarizationAiSegment(
        Long segmentId,
        String content,
        Long startedAtMs,
        Long endedAtMs
) {
}
