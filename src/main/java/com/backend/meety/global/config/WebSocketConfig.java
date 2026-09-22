package com.backend.meety.global.config;

import com.backend.meety.domain.recording.realtime.AudioChunkPolicy;
import com.backend.meety.domain.recording.realtime.AudioWebSocketHandler;
import com.backend.meety.domain.recording.realtime.AudioWebSocketHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketConfigurer {

    private final AudioWebSocketHandler audioWebSocketHandler;
    private final AudioWebSocketHandshakeInterceptor audioWebSocketHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(audioWebSocketHandler, "/ws/v1/recordings/{recordingSessionId}/audio")
                .addInterceptors(audioWebSocketHandshakeInterceptor)
                .setAllowedOriginPatterns("*");
    }

    /**
     * 설정하지 않으면 컨테이너 기본값(8KB)이 적용되어, 그보다 큰 오디오 청크가 오면
     * handleBinaryMessage 호출 없이 연결이 끊긴다.
     */
    @Bean
    public ServletServerContainerFactoryBean createWebSocketContainer() {
        ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
        container.setMaxBinaryMessageBufferSize(AudioChunkPolicy.MAX_CHUNK_BYTES);
        return container;
    }
}
