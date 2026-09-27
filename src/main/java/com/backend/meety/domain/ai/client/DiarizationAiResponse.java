package com.backend.meety.domain.ai.client;

import java.util.List;

public record DiarizationAiResponse(
        Long meetingId,
        List<DiarizationAiResultSegment> segments
) {
}
