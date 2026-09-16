package com.backend.meety.global.config;

import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class RestClientConfig {

    private static final Duration OAUTH_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration OAUTH_READ_TIMEOUT = Duration.ofSeconds(5);

    // 외부 서비스별로 타임아웃 정책이 다르므로 대상별 빈으로 분리해 관리한다
    @Bean
    public RestClient oauthRestClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(OAUTH_CONNECT_TIMEOUT);
        factory.setReadTimeout(OAUTH_READ_TIMEOUT);
        return RestClient.builder()
                .requestFactory(factory)
                .build();
    }
}
