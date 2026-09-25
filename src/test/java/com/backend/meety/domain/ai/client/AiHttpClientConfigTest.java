package com.backend.meety.domain.ai.client;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiHttpClientConfigTest {

    @Test
    @DisplayName("websocket-url에서 HTTP base URL을 유도한다")
    void derivesHttpBaseUrlFromWebsocketUrl() {
        assertThat(AiHttpClientConfig.httpBaseUrl(URI.create("ws://localhost:8000/v1/live-meeting")))
                .isEqualTo("http://localhost:8000");
        assertThat(AiHttpClientConfig.httpBaseUrl(URI.create("wss://ai.meety.io.kr/v1/live-meeting")))
                .isEqualTo("https://ai.meety.io.kr");
    }
}
