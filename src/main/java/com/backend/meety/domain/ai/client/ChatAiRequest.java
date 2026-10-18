package com.backend.meety.domain.ai.client;

import java.util.List;

public record ChatAiRequest(
        Long aiRequestId,
        Long meetingId,
        Long teamId,
        String question,
        List<ChatAiHistoryMessage> conversationHistory,
        List<ChatAiTranscriptSegment> transcriptSegments
) {
}
