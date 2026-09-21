package com.backend.meety.global.config;

import com.backend.meety.domain.recording.realtime.AudioWebSocketHandler;
import com.backend.meety.domain.recording.realtime.AudioWebSocketHandshakeInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

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
}
