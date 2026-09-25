package com.backend.meety.domain.ai.client;

public record SummaryAiSpeaker(
        Long speakerId,
        Long teamMemberId,
        String displayName
) {
}
