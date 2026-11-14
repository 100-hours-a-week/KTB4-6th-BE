package com.backend.meety.domain.ai.client;

import com.backend.meety.domain.ai.entity.AiRequestType;
import java.util.List;

public record ChatAiRequest(
        Long aiRequestId,
        Long meetingId,
        Long teamId,
        String question,
        List<ChatAiHistoryMessage> conversationHistory,
        List<ChatAiTranscriptSegment> transcriptSegments
) implements AiRequestInput {

    @Override
    public AiRequestType requestType() {
        return AiRequestType.CHAT;
    }
}
