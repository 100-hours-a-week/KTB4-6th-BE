package com.backend.meety.domain.ai.client;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class AiHttpClientConfig {

    private static final Duration AI_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration AI_READ_TIMEOUT = Duration.ofSeconds(60);
    /**
     * 화자 분리는 AI가 최대 3600초 처리하므로 그보다 길게 잡아 AI의 최종 응답(200·502·504)을 받는다.
     */
    private static final Duration DIARIZATION_READ_TIMEOUT = Duration.ofSeconds(3660);

    @Bean
    public RestClient aiRestClient(@Value("${ai.http-url}") String aiHttpUrl) {
        return build(aiHttpUrl, AI_READ_TIMEOUT);
    }

    @Bean
    public RestClient diarizationRestClient(@Value("${ai.http-url}") String aiHttpUrl) {
        return build(aiHttpUrl, DIARIZATION_READ_TIMEOUT);
    }

    private RestClient build(String baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(AI_CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(factory)
                .build();
    }
}
