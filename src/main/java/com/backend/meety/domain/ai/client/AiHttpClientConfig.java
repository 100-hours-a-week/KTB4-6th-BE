package com.backend.meety.domain.ai.client;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingProperties;
import java.net.URI;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * AI HTTP API는 현재 실시간 전사 WebSocket과 같은 서버를 쓰므로 websocket-url에서 base URL을 유도한다.
 * 서버가 분리되면 별도 설정으로 바꾼다.
 */
@Configuration
public class AiHttpClientConfig {

    private static final Duration AI_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration AI_READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    public RestClient aiRestClient(AiLiveMeetingProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(AI_CONNECT_TIMEOUT);
        factory.setReadTimeout(AI_READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(httpBaseUrl(properties.websocketUrl()))
                .requestFactory(factory)
                .build();
    }

    static String httpBaseUrl(URI websocketUrl) {
        String scheme = "wss".equals(websocketUrl.getScheme()) ? "https" : "http";
        return scheme + "://" + websocketUrl.getAuthority();
    }
}
