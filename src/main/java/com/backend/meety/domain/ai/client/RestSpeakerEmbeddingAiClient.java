package com.backend.meety.domain.ai.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class RestSpeakerEmbeddingAiClient implements SpeakerEmbeddingAiClient {

    private final RestClient aiRestClient;

    @Override
    public SpeakerEmbeddingAiResponse requestEmbedding(SpeakerEmbeddingAiRequest request) {
        return aiRestClient.post()
                .uri(AiApiPath.SPEAKER_EMBEDDING)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(SpeakerEmbeddingAiResponse.class);
    }
}
