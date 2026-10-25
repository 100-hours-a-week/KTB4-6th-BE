package com.backend.meety.domain.ai.client;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestSttPrewarmAiClient implements SttPrewarmAiClient {

    private final RestClient prewarmRestClient;

    public RestSttPrewarmAiClient(@Qualifier("prewarmRestClient") RestClient prewarmRestClient) {
        this.prewarmRestClient = prewarmRestClient;
    }

    @Override
    public HttpStatusCode requestPrewarm(SttPrewarmRequest request) {
        return prewarmRestClient.post()
                .uri(AiApiPath.STT_PREWARM)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .exchange((httpRequest, httpResponse) -> httpResponse.getStatusCode());
    }
}
