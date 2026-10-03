package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class AudioWebSocketRegistryTest {

    @Test
    void streamStateIsSharedPerRecordingUntilRemoved() {
        AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
        registry.streamState(700L).markProcessed(3L);

        assertThat(registry.streamState(700L).lastProcessedSequence()).isEqualTo(3L);
        assertThat(registry.streamState(701L).lastProcessedSequence()).isZero();

        registry.removeStreamState(700L);

        assertThat(registry.streamState(700L).lastProcessedSequence()).isZero();
    }

    @Test
    void removeIsSafeWhenCalledRepeatedlyForSameSession() {
        AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
        WebSocketSession session = mock(WebSocketSession.class);

        assertThat(registry.replace(700L, session)).isEmpty();

        registry.remove(700L, session);
        registry.remove(700L, session);

        assertThat(registry.find(700L)).isEmpty();
        assertThat(registry.count()).isZero();
    }

    @Test
    void staleSessionRemoveDoesNotRemoveCurrentSession() {
        AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
        WebSocketSession stale = mock(WebSocketSession.class);
        WebSocketSession current = mock(WebSocketSession.class);

        assertThat(registry.replace(700L, current)).isEmpty();

        registry.remove(700L, stale);

        assertThat(registry.find(700L)).contains(current);
        assertThat(registry.count()).isOne();
    }

    @Test
    void replaceReturnsPreviousSessionAndKeepsNewOne() {
        AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
        WebSocketSession previous = mock(WebSocketSession.class);
        WebSocketSession current = mock(WebSocketSession.class);
        registry.replace(700L, previous);

        assertThat(registry.replace(700L, current)).contains(previous);
        assertThat(registry.find(700L)).contains(current);
    }

    @Test
    void closeAndRemoveClosesSessionAndRemovesItEvenWhenCloseFails() throws Exception {
        AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
        WebSocketSession session = mock(WebSocketSession.class);
        doThrow(new IOException("already closed")).when(session).close(any(CloseStatus.class));
        registry.replace(700L, session);

        registry.closeAndRemove(700L, session, AudioWebSocketHandler.SUPERSEDED);

        verify(session).close(AudioWebSocketHandler.SUPERSEDED);
        assertThat(registry.find(700L)).isEmpty();
    }
}
