package com.backend.meety.domain.ai.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class RestSummaryAiClient implements SummaryAiClient {

    private final RestClient aiRestClient;

    @Override
    public String requestSummary(SummaryAiRequest request) {
        return aiRestClient.post()
                .uri("/v1/summary")
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(String.class);
    }
}
