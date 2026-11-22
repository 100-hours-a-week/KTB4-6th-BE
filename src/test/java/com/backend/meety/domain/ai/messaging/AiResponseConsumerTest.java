package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.entity.AiRequestType;
import com.rabbitmq.client.Channel;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.CannotCreateTransactionException;

class AiResponseConsumerTest {

    private final AiResponseMessageParser parser = mock(AiResponseMessageParser.class);
    private final AiResponseService responseService = mock(AiResponseService.class);
    private final AiResponseConsumerControl consumerControl = mock(AiResponseConsumerControl.class);
    private final AiResponseConsumer consumer = new AiResponseConsumer(
            parser, responseService, consumerControl, new AiResponseProperties(3, Duration.ZERO));
    private final Channel channel = mock(Channel.class);
    private final AiResponseMessage response = new AiResponseMessage(
            1, 101L, "token-1", AiRequestType.SUMMARY, AiResponseEventType.FAILED, null, new AiResponseError("x"));
    private final Message message = message();

    @Test
    @DisplayName("일시적인 DB 오류 후 저장 재시도에 성공하면 ACK한다")
    void acknowledgesAfterSaveRetrySucceeds() throws Exception {
        when(parser.parse(message.getBody())).thenReturn(Optional.of(response));
        doThrow(new QueryTimeoutException("timeout"))
                .doThrow(new CannotCreateTransactionException("no connection"))
                .doNothing()
                .when(responseService).handle(response);

        consumer.consume(message, channel);

        verify(responseService, times(3)).handle(response);
        verify(channel).basicAck(7L, false);
        verify(consumerControl, never()).stopForDatabaseFailure();
    }

    @Test
    @DisplayName("저장 재시도 한도를 넘으면 ACK하지 않고 Consumer 수신을 중단한다")
    void stopsConsumerWhenSaveRetriesExhausted() throws Exception {
        when(parser.parse(message.getBody())).thenReturn(Optional.of(response));
        doThrow(new QueryTimeoutException("timeout")).when(responseService).handle(response);

        consumer.consume(message, channel);

        verify(responseService, times(3)).handle(response);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
        verify(consumerControl).stopForDatabaseFailure();
    }

    @Test
    @DisplayName("DB 오류가 아닌 예외는 재시도하거나 Consumer를 중단하지 않는다")
    void doesNotRetryNonDatabaseFailure() throws Exception {
        when(parser.parse(message.getBody())).thenReturn(Optional.of(response));
        doThrow(new IllegalStateException("no handler")).when(responseService).handle(response);

        assertThatThrownBy(() -> consumer.consume(message, channel)).isInstanceOf(IllegalStateException.class);

        verify(responseService, times(1)).handle(response);
        verify(consumerControl, never()).stopForDatabaseFailure();
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
    }

    @Test
    @DisplayName("형식이 잘못된 응답은 저장하지 않고 requeue 없이 거절한다")
    void rejectsInvalidResponse() throws Exception {
        when(parser.parse(message.getBody())).thenReturn(Optional.empty());

        consumer.consume(message, channel);

        verify(channel).basicNack(7L, false, false);
        verify(responseService, never()).handle(response);
    }

    private static Message message() {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(7L);
        return new Message("{}".getBytes(), properties);
    }
}
