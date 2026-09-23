package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.event.AiLiveMeetingReadyEvent;
import java.io.IOException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class AudioWebSocketReadyListenerTest {

    private final AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AudioWebSocketReadyListener listener =
            new AudioWebSocketReadyListener(registry, objectMapper);

    @Test
    @DisplayName("AI 준비 완료 이벤트를 받으면 연결된 FE에 ready 신호를 전송한다")
    void sendsReadyMessageToConnectedSession() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        registry.register(88L, session);

        listener.notifyReady(new AiLiveMeetingReadyEvent(88L));

        ArgumentCaptor<WebSocketMessage<?>> captor = ArgumentCaptor.forClass(WebSocketMessage.class);
        verify(session).sendMessage(captor.capture());
        assertThat(captor.getValue()).isInstanceOf(TextMessage.class);
        assertThat((String) captor.getValue().getPayload()).isEqualTo("{\"type\":\"ready\"}");
    }

    @Test
    @DisplayName("연결된 FE가 없으면 아무것도 전송하지 않는다")
    void doesNothingWhenSessionMissing() {
        listener.notifyReady(new AiLiveMeetingReadyEvent(88L));

        assertThat(registry.count()).isZero();
    }

    @Test
    @DisplayName("ready 전송에 실패해도 예외를 밖으로 던지지 않는다")
    void swallowsSendFailure() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        doThrow(new IOException("closed")).when(session).sendMessage(any());
        registry.register(88L, session);

        listener.notifyReady(new AiLiveMeetingReadyEvent(88L));

        verify(session, never()).close();
    }
}
