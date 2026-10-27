package com.backend.meety.domain.ai.client;

import java.util.List;

public record SpeakerEmbeddingAiResponse(
        String modelVersion,
        List<Double> embedding
) {
}
