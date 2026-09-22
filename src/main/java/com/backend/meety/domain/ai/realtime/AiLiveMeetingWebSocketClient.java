package com.backend.meety.domain.ai.realtime;

import java.net.URI;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

public interface AiLiveMeetingWebSocketClient {

    WebSocketSession connect(WebSocketHandler handler, URI uri);
}
