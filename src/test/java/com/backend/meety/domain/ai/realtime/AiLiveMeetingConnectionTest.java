package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class AiLiveMeetingConnectionTest {

    @Test
    void forwardsAudioMetaImmediatelyBeforeBinaryAndIncrementsSequence() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        AiLiveMeetingConnection connection =
                new AiLiveMeetingConnection(new ObjectMapper(), 100L, 700L, "webm_opus");
        connection.attach(session);
        connection.markReady();

        assertThat(connection.forwardAudio(new byte[]{1, 2, 3})).isTrue();
        assertThat(connection.forwardAudio(new byte[]{4, 5})).isTrue();

        InOrder inOrder = inOrder(session);
        inOrder.verify(session).sendMessage(argThat(message ->
                message instanceof TextMessage text && text.getPayload().contains("\"sequence\":0")));
        inOrder.verify(session).sendMessage(argThat(message ->
                message instanceof BinaryMessage binary && binary.getPayloadLength() == 3));
        inOrder.verify(session).sendMessage(argThat(message ->
                message instanceof TextMessage text && text.getPayload().contains("\"sequence\":1")));
        inOrder.verify(session).sendMessage(argThat(message ->
                message instanceof BinaryMessage binary && binary.getPayloadLength() == 2));
    }

    @Test
    void blocksAudioBeforeReadyAndInvalidSize() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        AiLiveMeetingConnection connection =
                new AiLiveMeetingConnection(new ObjectMapper(), 100L, 700L, "webm_opus");
        connection.attach(session);

        assertThat(connection.forwardAudio(new byte[]{1})).isFalse();
        connection.markReady();
        assertThat(connection.forwardAudio(new byte[0])).isFalse();
        assertThat(connection.forwardAudio(new byte[262_145])).isFalse();
    }
}
