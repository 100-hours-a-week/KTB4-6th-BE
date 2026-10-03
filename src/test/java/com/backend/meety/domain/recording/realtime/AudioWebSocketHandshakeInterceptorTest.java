package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.ai.realtime.AudioFormat;
import com.backend.meety.global.security.WebSocketCookieAuthentication;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;

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

    @Test
    @DisplayName("같은 녹음 세션의 이전 소켓을 닫고 AI 연결을 준비한 뒤 현재 스트림 세대를 컨텍스트에 담는다")
    void supersedesPreviousSocketAndStoresStreamEpoch() throws Exception {
        ServerHttpRequest request = request("ws://localhost/ws/v1/recordings/88/audio?audioFormat=webm_opus");
        AudioWebSocketContext context = new AudioWebSocketContext(7L, 42L, 88L, AudioFormat.WEBM_OPUS);
        org.mockito.Mockito.when(authentication.authenticate(request)).thenReturn(7L);
        org.mockito.Mockito.when(accessService.validate(7L, 88L, AudioFormat.WEBM_OPUS)).thenReturn(context);
        org.mockito.Mockito.when(aiConnectionService.prepareStream(context)).thenReturn(OptionalLong.of(2L));
        WebSocketSession previous = mock(WebSocketSession.class);
        registry.replace(88L, previous);
        Map<String, Object> attributes = new HashMap<>();

        boolean result = interceptor.beforeHandshake(
                request, new TestServerHttpResponse(), mock(WebSocketHandler.class), attributes);

        assertThat(result).isTrue();
        verify(previous).close(AudioWebSocketHandler.SUPERSEDED);
        assertThat(registry.find(88L)).isEmpty();
        AudioWebSocketContext stored =
                (AudioWebSocketContext) attributes.get(AudioWebSocketHandshakeInterceptor.CONTEXT_ATTRIBUTE);
        assertThat(stored.streamEpoch()).isEqualTo(2L);
    }

    @Test
    @DisplayName("AI 연결을 준비하지 못하면 503으로 거절한다")
    void rejectsWithServiceUnavailableWhenStreamCannotOpen() {
        ServerHttpRequest request = request("ws://localhost/ws/v1/recordings/88/audio?audioFormat=webm_opus");
        AudioWebSocketContext context = new AudioWebSocketContext(7L, 42L, 88L, AudioFormat.WEBM_OPUS);
        org.mockito.Mockito.when(authentication.authenticate(request)).thenReturn(7L);
        org.mockito.Mockito.when(accessService.validate(7L, 88L, AudioFormat.WEBM_OPUS)).thenReturn(context);
        org.mockito.Mockito.when(aiConnectionService.prepareStream(context)).thenReturn(OptionalLong.empty());
        TestServerHttpResponse response = new TestServerHttpResponse();

        boolean result = interceptor.beforeHandshake(
                request, response, mock(WebSocketHandler.class), new HashMap<>());

        assertThat(result).isFalse();
        assertThat(response.statusCode).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("업그레이드가 실패해도 재사용 중인 AI 연결을 종료하지 않는다")
    void afterHandshakeFailureDoesNotStopAiConnection() {
        interceptor.afterHandshake(
                request("ws://localhost/ws/v1/recordings/88/audio?audioFormat=webm_opus"),
                new TestServerHttpResponse(),
                mock(WebSocketHandler.class),
                new RuntimeException("upgrade failed"));

        verifyNoInteractions(aiConnectionService);
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
