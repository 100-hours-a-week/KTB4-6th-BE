package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

class AiRequestPublisherTest {

    private final RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
    private final AiRequestPublisher publisher = new AiRequestPublisher(
            rabbitTemplate, new AiRequestPublishProperties(20, Duration.ofMillis(100)));
    private final AiRequestOutboxRecord outboxRecord =
            new AiRequestOutboxRecord(101L, "token-1", "summary", "{\"schemaVersion\":1}");

    @Test
    @DisplayName("Confirm ACK를 받고 Return이 없으면 CONFIRMED")
    void confirmed() {
        completeConfirm(true, false);

        assertThat(publisher.publish(outboxRecord)).isEqualTo(AiRequestPublishResult.CONFIRMED);
    }

    @Test
    @DisplayName("Confirm ACK를 받아도 Return된 메시지면 RETURNED")
    void returned() {
        completeConfirm(true, true);

        assertThat(publisher.publish(outboxRecord)).isEqualTo(AiRequestPublishResult.RETURNED);
    }

    @Test
    @DisplayName("Publisher NACK이면 NACKED")
    void nacked() {
        completeConfirm(false, false);

        assertThat(publisher.publish(outboxRecord)).isEqualTo(AiRequestPublishResult.NACKED);
    }

    @Test
    @DisplayName("발행 중 연결 예외가 나면 FAILED")
    void failed() {
        doThrow(new AmqpConnectException(new RuntimeException("connection refused")))
                .when(rabbitTemplate).send(any(), any(), any(Message.class), any(CorrelationData.class));

        assertThat(publisher.publish(outboxRecord)).isEqualTo(AiRequestPublishResult.FAILED);
    }

    @Test
    @DisplayName("Confirm 대기 시간 안에 응답이 없으면 UNCONFIRMED")
    void unconfirmed() {
        assertThat(publisher.publish(outboxRecord)).isEqualTo(AiRequestPublishResult.UNCONFIRMED);
    }

    private void completeConfirm(boolean ack, boolean returned) {
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            if (returned) {
                correlation.setReturned(new ReturnedMessage(
                        new Message(new byte[0], new MessageProperties()), 312, "NO_ROUTE",
                        AiRabbitTopology.REQUEST_EXCHANGE, "summary"));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(ack, null));
            return null;
        }).when(rabbitTemplate).send(eq(AiRabbitTopology.REQUEST_EXCHANGE), eq("summary"),
                any(Message.class), any(CorrelationData.class));
    }
}
