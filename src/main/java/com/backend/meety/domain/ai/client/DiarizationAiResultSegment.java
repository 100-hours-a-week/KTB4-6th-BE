package com.backend.meety.domain.ai.client;

public record DiarizationAiResultSegment(
        Long segmentId,
        String content,
        Long startedAtMs,
        Long endedAtMs,
        Long speakerId
) {
}
