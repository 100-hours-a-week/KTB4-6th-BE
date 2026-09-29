package com.backend.meety.domain.recording.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.WebSocketSession;

class AudioWebSocketRegistryTest {

    @Test
    void removeIsSafeWhenCalledRepeatedlyForSameSession() {
        AudioWebSocketRegistry registry = new AudioWebSocketRegistry();
        WebSocketSession session = mock(WebSocketSession.class);

        assertThat(registry.register(700L, session)).isTrue();

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

        assertThat(registry.register(700L, current)).isTrue();

        registry.remove(700L, stale);

        assertThat(registry.find(700L)).contains(current);
        assertThat(registry.count()).isOne();
    }
}
