package com.backend.meety.domain.ai.client;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
@RequiredArgsConstructor
public class RestChatAiClient implements ChatAiClient {

    private final RestClient aiRestClient;

    @Override
    public ChatAiResponse requestAnswer(ChatAiRequest request) {
        return aiRestClient.post()
                .uri(AiApiPath.CHAT)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(ChatAiResponse.class);
    }
}
