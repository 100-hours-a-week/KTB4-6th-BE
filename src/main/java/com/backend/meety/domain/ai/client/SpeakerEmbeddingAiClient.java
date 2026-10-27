package com.backend.meety.domain.ai.client;

public interface SpeakerEmbeddingAiClient {

    SpeakerEmbeddingAiResponse requestEmbedding(SpeakerEmbeddingAiRequest request);
}
