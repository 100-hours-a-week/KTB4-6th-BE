package com.backend.meety.domain.ai.realtime;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.client.WebSocketClient;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

@Configuration
@EnableConfigurationProperties({AiLiveMeetingProperties.class, AiAudioAckProperties.class,
        AiHeartbeatProperties.class, AiRecoveryProperties.class})
public class AiWebSocketClientConfig {

    @Bean
    public WebSocketClient aiWebSocketClient() {
        return new StandardWebSocketClient();
    }

    @Bean("aiRecoveryExecutor")
    public org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor aiRecoveryExecutor(AiRecoveryProperties properties) {
        var executor = new org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.workers());
        executor.setMaxPoolSize(properties.workers());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("ai-recovery-");
        executor.setRejectedExecutionHandler(new java.util.concurrent.ThreadPoolExecutor.AbortPolicy());
        return executor;
    }
}
