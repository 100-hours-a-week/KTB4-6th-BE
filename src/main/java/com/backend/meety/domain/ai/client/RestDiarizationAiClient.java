package com.backend.meety.domain.ai.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class RestDiarizationAiClient implements DiarizationAiClient {

    private final RestClient diarizationRestClient;

    @Override
    public DiarizationAiResponse requestDiarization(DiarizationAiRequest request) {
        return diarizationRestClient.post()
                .uri(AiApiPath.DIARIZATION)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(DiarizationAiResponse.class);
    }
}
