package com.backend.meety.domain.ai.client;

import org.springframework.http.HttpStatusCode;

public interface SttPrewarmAiClient {

    HttpStatusCode requestPrewarm(SttPrewarmRequest request);
}
