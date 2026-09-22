package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AudioFormat;
import com.backend.meety.global.security.WebSocketCookieAuthentication;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

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
            Long recordingSessionId = extractRecordingSessionId(request.getURI());
            AudioFormat audioFormat = extractAudioFormat(request.getURI());
            Long userId = authentication.authenticate(request);
            if (registry.exists(recordingSessionId)) {
                response.setStatusCode(HttpStatus.CONFLICT);
                return false;
            }
            attributes.put(CONTEXT_ATTRIBUTE, accessService.validate(userId, recordingSessionId, audioFormat));
            return true;
        } catch (IllegalArgumentException e) {
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
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

    private AudioFormat extractAudioFormat(URI uri) {
        if (uri.getQuery() == null || uri.getQuery().isBlank()) {
            throw new IllegalArgumentException("audioFormat is missing");
        }
        return Arrays.stream(uri.getQuery().split("&"))
                .map(value -> value.split("=", 2))
                .filter(values -> values.length == 2)
                .filter(values -> "audioFormat".equals(values[0]))
                .map(values -> AudioFormat.from(values[1])
                        .orElseThrow(() -> new IllegalArgumentException("unsupported audioFormat")))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("audioFormat is missing"));
    }
}
