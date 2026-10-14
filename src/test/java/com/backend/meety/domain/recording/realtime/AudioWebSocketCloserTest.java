package com.backend.meety.domain.recording.realtime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class AudioWebSocketCloserTest {

    private final AudioWebSocketRegistry registry = mock(AudioWebSocketRegistry.class);
    private final AudioWebSocketCloser closer = new AudioWebSocketCloser(registry);

    @Test
    void closeNormalAndRemoveSession() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(registry.find(700L)).thenReturn(Optional.of(session));

        closer.close(700L);

        verify(session).close(CloseStatus.NORMAL);
        verify(registry).remove(700L, session);
        verify(registry).removeStreamState(700L);
    }

    @Test
    void succeedsWhenWebSocketDoesNotExist() {
        when(registry.find(700L)).thenReturn(Optional.empty());

        closer.close(700L);

        verify(registry).find(700L);
        verify(registry).removeStreamState(700L);
    }

    @Test
    void removeSessionEvenWhenCloseFails() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(registry.find(700L)).thenReturn(Optional.of(session));
        doThrow(new IOException("close failed")).when(session).close(CloseStatus.NORMAL);

        closer.close(700L);

        verify(session).close(CloseStatus.NORMAL);
        verify(registry).remove(700L, session);
    }
}
