package com.backend.meety.domain.ai.client;

public interface ChatAiClient {

    ChatAiResponse requestAnswer(ChatAiRequest request);
}
