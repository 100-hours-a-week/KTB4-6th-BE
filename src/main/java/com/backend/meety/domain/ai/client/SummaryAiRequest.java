package com.backend.meety.domain.ai.client;

import com.backend.meety.domain.ai.entity.AiRequestType;
import java.util.List;

public record SummaryAiRequest(
        String requestId,
        Long meetingId,
        String title,
        String purpose,
        String note,
        String regenerationReason,
        String meetingStartedAt,
        List<SummaryAiSpeaker> speakers,
        List<SummaryAiSegment> segments
) implements AiRequestInput {

    @Override
    public AiRequestType requestType() {
        return AiRequestType.SUMMARY;
    }
}
