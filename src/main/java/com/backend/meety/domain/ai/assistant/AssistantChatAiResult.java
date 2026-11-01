package com.backend.meety.domain.ai.assistant;

import tools.jackson.databind.JsonNode;
import java.util.List;

public record AssistantChatAiResult(String answer, List<Long> referencedMeetingIds, JsonNode meetingDraft) {
}
