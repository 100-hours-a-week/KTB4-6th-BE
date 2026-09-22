package com.backend.meety.domain.ai.realtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@Configuration
@EnableConfigurationProperties(AiLiveMeetingProperties.class)
public class AiWebSocketClientConfig {

    @Bean
    public WebSocketClient aiWebSocketClient() {
        return new StandardWebSocketClient();
    }
}
