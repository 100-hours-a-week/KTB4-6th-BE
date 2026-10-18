package com.backend.meety.domain.ai.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class RestMetricAiClient implements MetricAiClient {

    private final RestClient aiRestClient;

    @Override
    public MetricAiResponse requestMetric(MetricAiRequest request) {
        MetricAiResponse response = aiRestClient.post()
                .uri(AiApiPath.METRIC)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(MetricAiResponse.class);
        if (response == null) {
            throw new IllegalStateException("AI metric response body is empty");
        }
        return response;
    }
}
