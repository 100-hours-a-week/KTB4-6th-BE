package com.backend.meety.domain.ai.client;

import java.util.List;

public record ChatAiResponse(
        Long aiRequestId,
        String answer,
        List<ChatAiCitation> citations
) {
}
