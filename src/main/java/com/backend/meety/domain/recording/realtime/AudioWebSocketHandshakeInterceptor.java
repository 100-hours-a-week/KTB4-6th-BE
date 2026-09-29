package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.ai.realtime.AudioFormat;
import com.backend.meety.global.security.WebSocketCookieAuthentication;
import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioWebSocketHandshakeInterceptor implements HandshakeInterceptor {

    public static final String CONTEXT_ATTRIBUTE = "audioWebSocketContext";

    private final WebSocketCookieAuthentication authentication;
    private final AudioWebSocketAccessService accessService;
    private final AudioWebSocketRegistry registry;
    private final AiLiveMeetingConnectionService aiConnectionService;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        try {
            Long recordingSessionId = extractRecordingSessionId(request.getURI());
            AudioFormat audioFormat = extractAudioFormat(request.getURI());
            Long userId = authentication.authenticate(request);
            if (registry.exists(recordingSessionId)) {
                log.warn("Audio WebSocket 핸드셰이크를 거절했습니다. recordingSessionId={}, reason=already connected",
                        recordingSessionId);
                response.setStatusCode(HttpStatus.CONFLICT);
                return false;
            }
            AudioWebSocketContext context = accessService.validate(userId, recordingSessionId, audioFormat);
            if (!aiConnectionService.startAndAwaitReady(context)) {
                log.warn("Audio WebSocket 핸드셰이크를 거절했습니다. recordingSessionId={}, reason=ai not ready",
                        recordingSessionId);
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return false;
            }
            attributes.put(CONTEXT_ATTRIBUTE, context);
            log.info("Audio WebSocket 핸드셰이크를 승인했습니다. recordingSessionId={}, meetingId={}, openSessions={}",
                    recordingSessionId, context.meetingId(), registry.count());
            return true;
        } catch (IllegalArgumentException e) {
            log.warn("Audio WebSocket 핸드셰이크를 거절했습니다. uri={}, reason=invalid request, message={}",
                    request.getURI().getPath(), e.getMessage());
            response.setStatusCode(HttpStatus.BAD_REQUEST);
            return false;
        } catch (RuntimeException e) {
            log.warn("Audio WebSocket 핸드셰이크를 거절했습니다. uri={}, reason=access denied",
                    request.getURI().getPath(), e);
            response.setStatusCode(HttpStatus.FORBIDDEN);
            return false;
        }
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        if (exception == null) {
            return;
        }
        try {
            aiConnectionService.stop(extractRecordingSessionId(request.getURI()));
        } catch (RuntimeException e) {
            log.warn("핸드셰이크 실패 후 AI 연결 정리에 실패했습니다. uri={}", request.getURI().getPath(), e);
        }
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
