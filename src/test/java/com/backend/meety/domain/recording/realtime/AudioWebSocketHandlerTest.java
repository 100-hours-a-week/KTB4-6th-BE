package com.backend.meety.domain.recording.realtime;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.ai.realtime.AudioFormat;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class AudioWebSocketHandlerTest {

    private final AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
    private final AiLiveMeetingConnectionService aiConnectionService = mock(AiLiveMeetingConnectionService.class);
    private final AudioWebSocketHandler handler = new AudioWebSocketHandler(registry, aiConnectionService);

    @Test
    void startsAiConnectionAfterFeAudioWebSocketRegistered() throws Exception {
        AudioWebSocketContext context = context();
        WebSocketSession session = session(context);
        when(aiConnectionService.start(context)).thenReturn(true);

        handler.afterConnectionEstablished(session);

        verify(aiConnectionService).start(context);
        org.assertj.core.api.Assertions.assertThat(registry.find(88L)).contains(session);
    }

    @Test
    void removesFeRegistryAndClosesSessionWhenAiConnectionFails() throws Exception {
        AudioWebSocketContext context = context();
        WebSocketSession session = session(context);
        when(aiConnectionService.start(context)).thenReturn(false);

        handler.afterConnectionEstablished(session);

        org.assertj.core.api.Assertions.assertThat(registry.find(88L)).isEmpty();
        verify(session).close(CloseStatus.SERVER_ERROR.withReason("ai websocket connection failed"));
    }

    private AudioWebSocketContext context() {
        return new AudioWebSocketContext(7L, 42L, 88L, AudioFormat.WEBM_OPUS);
    }

    private WebSocketSession session(AudioWebSocketContext context) {
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put(AudioWebSocketHandshakeInterceptor.CONTEXT_ATTRIBUTE, context);
        when(session.getAttributes()).thenReturn(attributes);
        return session;
    }
}
