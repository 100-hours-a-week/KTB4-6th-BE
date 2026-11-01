package com.backend.meety.domain.ai.assistant;

import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
@EnableConfigurationProperties(AssistantChatProperties.class)
public class AssistantChatConfig {

    @Bean
    public RestClient assistantChatRestClient(@Value("${ai.http-url}") String aiHttpUrl,
                                               AssistantChatProperties properties) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(3));
        factory.setReadTimeout(properties.readTimeout());
        return RestClient.builder().baseUrl(aiHttpUrl).requestFactory(factory).build();
    }
}
