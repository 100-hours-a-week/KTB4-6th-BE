package com.backend.meety.domain.ai.realtime;

import java.net.URI;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

@Component
@RequiredArgsConstructor
public class StandardAiLiveMeetingWebSocketClient implements AiLiveMeetingWebSocketClient {

    private final WebSocketClient webSocketClient;

    @Override
    public WebSocketSession connect(WebSocketHandler handler, URI uri) {
        try {
            return webSocketClient.execute(handler, uri.toString()).join();
        } catch (CompletionException e) {
            throw e;
        }
    }
}
