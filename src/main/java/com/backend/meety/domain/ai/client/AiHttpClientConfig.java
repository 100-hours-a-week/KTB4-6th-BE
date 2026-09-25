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

    @Bean
    public RestClient aiRestClient(@Value("${ai.http-url}") String aiHttpUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(AI_CONNECT_TIMEOUT);
        factory.setReadTimeout(AI_READ_TIMEOUT);
        return RestClient.builder()
                .baseUrl(aiHttpUrl)
                .requestFactory(factory)
                .build();
    }
}
