package com.backend.meety.domain.ai.assistant.dto;

import com.backend.meety.domain.ai.entity.AiRequestStatus;
import tools.jackson.databind.JsonNode;
import java.time.LocalDateTime;

public record AssistantChatTurnResponse(
        Long aiRequestId,
        Long messageId,
        String question,
        String answer,
        AiRequestStatus status,
        String failureCode,
        JsonNode meetingDraft,
        Long createdMeetingId,
        java.util.List<Long> referencedMeetingIds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
