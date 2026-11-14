package com.backend.meety.domain.ai.client;

import com.backend.meety.domain.ai.entity.AiRequestType;
import java.util.List;

public record DiarizationAiRequest(
        String requestId,
        Long meetingId,
        String audioUrl,
        List<DiarizationAiSegment> segments
) implements AiRequestInput {

    @Override
    public AiRequestType requestType() {
        return AiRequestType.DIARIZATION;
    }
}
