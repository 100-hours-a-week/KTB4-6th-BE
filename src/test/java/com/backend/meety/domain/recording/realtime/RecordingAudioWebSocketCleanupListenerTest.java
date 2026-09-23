package com.backend.meety.domain.recording.realtime;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Optional;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

class RecordingAudioWebSocketCleanupListenerTest {

    private final AudioWebSocketRegistry registry = mock(AudioWebSocketRegistry.class);
    private final RecordingAudioWebSocketCleanupListener listener =
            new RecordingAudioWebSocketCleanupListener(registry);

    @Test
    void closeNormalAndRemoveSessionAfterRecordingCompleted() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(registry.find(700L)).thenReturn(Optional.of(session));

        listener.closeAudioWebSocket(new RecordingCompletedEvent(100L, 700L));

        verify(session).close(CloseStatus.NORMAL);
        verify(registry).remove(700L, session);
    }

    @Test
    void completedRecordingSucceedsWhenWebSocketDoesNotExist() {
        when(registry.find(700L)).thenReturn(Optional.empty());

        listener.closeAudioWebSocket(new RecordingCompletedEvent(100L, 700L));

        verify(registry).find(700L);
    }

    @Test
    void removeSessionEvenWhenCloseFails() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(registry.find(700L)).thenReturn(Optional.of(session));
        doThrow(new IOException("close failed")).when(session).close(CloseStatus.NORMAL);

        listener.closeAudioWebSocket(new RecordingCompletedEvent(100L, 700L));

        verify(session).close(CloseStatus.NORMAL);
        verify(registry).remove(700L, session);
    }

    @Test
    void cleanupRunsAfterCommit() throws Exception {
        Method method = RecordingAudioWebSocketCleanupListener.class
                .getMethod("closeAudioWebSocket", RecordingCompletedEvent.class);

        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        Assertions.assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }
}
