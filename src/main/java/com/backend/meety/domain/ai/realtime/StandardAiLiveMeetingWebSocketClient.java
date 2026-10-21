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

    @Override
    public WebSocketSession connect(WebSocketHandler handler, URI uri, java.time.Duration timeout) {
        var future = webSocketClient.execute(handler, uri.toString());
        try {
            return future.get(Math.max(1, timeout.toMillis()), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            closeLateConnection(future);
            throw new CompletionException(e);
        } catch (java.util.concurrent.TimeoutException e) {
            closeLateConnection(future);
            throw new CompletionException(e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new CompletionException(e.getCause());
        }
    }

    private void closeLateConnection(java.util.concurrent.CompletableFuture<WebSocketSession> future) {
        future.thenAccept(session -> {
            try { session.close(); }
            catch (Exception e) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("늦게 연결된 AI socket close 실패", e); }
        });
    }
}
