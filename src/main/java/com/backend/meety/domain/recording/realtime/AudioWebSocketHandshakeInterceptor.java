package com.backend.meety.domain.recording.realtime;

import com.backend.meety.global.security.WebSocketCookieAuthentication;
import java.net.URI;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.UriComponentsBuilder;

@Component
@RequiredArgsConstructor
public class AudioWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String CONTEXT_ATTRIBUTE = "audioWebSocketContext";

    private final WebSocketCookieAuthentication authentication;
    private final AudioWebSocketAccessService accessService;
    private final AudioWebSocketRegistry registry;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        try {
            Long userId = authentication.authenticate(request);
            Long recordingSessionId = extractRecordingSessionId(request.getURI());
            if (registry.exists(recordingSessionId)) {
                response.setStatusCode(HttpStatus.CONFLICT);
                return false;
            }
            String audioFormat = UriComponentsBuilder.fromUri(request.getURI())
                    .build()
                    .getQueryParams()
                    .getFirst("audioFormat");
            AudioWebSocketContext context = accessService.validate(userId, recordingSessionId, audioFormat);
            attributes.put(CONTEXT_ATTRIBUTE, context);
            return true;
        } catch (RuntimeException e) {
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
    }

    private Long extractRecordingSessionId(URI uri) {
        String[] values = uri.getPath().split("/");
        if (values.length < 5) {
            throw new IllegalArgumentException("recordingSessionId is missing");
        }
        return Long.valueOf(values[4]);
    }
}
