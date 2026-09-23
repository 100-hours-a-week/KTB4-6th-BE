package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.global.security.WebSocketCookieAuthentication;
import java.net.URI;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

class AudioWebSocketHandshakeInterceptorTest {

    private final WebSocketCookieAuthentication authentication = mock(WebSocketCookieAuthentication.class);
    private final AudioWebSocketAccessService accessService = mock(AudioWebSocketAccessService.class);
    private final AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
    private final AiLiveMeetingConnectionService aiConnectionService = mock(AiLiveMeetingConnectionService.class);
    private final AudioWebSocketHandshakeInterceptor interceptor =
            new AudioWebSocketHandshakeInterceptor(authentication, accessService, registry, aiConnectionService);

    @Test
    void unsupportedAudioFormatRejectsHandshakeBeforeAiSessionStartCanRun() {
        TestServerHttpResponse response = new TestServerHttpResponse();

        boolean result = interceptor.beforeHandshake(
                request("ws://localhost/ws/v1/recordings/88/audio?audioFormat=wav"),
                response,
                mock(WebSocketHandler.class),
                new HashMap<>()
        );

        assertThat(result).isFalse();
        assertThat(response.statusCode).isEqualTo(HttpStatus.BAD_REQUEST);
        verifyNoInteractions(authentication, accessService, aiConnectionService);
    }

    private ServerHttpRequest request(String uri) {
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        org.mockito.Mockito.when(request.getURI()).thenReturn(URI.create(uri));
        return request;
    }

    private static class TestServerHttpResponse implements ServerHttpResponse {

        private HttpStatus statusCode;

        @Override
        public void setStatusCode(org.springframework.http.HttpStatusCode status) {
            this.statusCode = HttpStatus.valueOf(status.value());
        }

        @Override
        public org.springframework.http.HttpHeaders getHeaders() {
            return new org.springframework.http.HttpHeaders();
        }

        @Override
        public java.io.OutputStream getBody() {
            return java.io.OutputStream.nullOutputStream();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
