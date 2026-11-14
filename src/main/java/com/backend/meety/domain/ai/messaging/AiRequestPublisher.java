package com.backend.meety.domain.ai.messaging;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.connection.CorrelationData.Confirm;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiRequestPublisher {

    private final RabbitTemplate rabbitTemplate;
    private final AiRequestPublishProperties properties;

    public AiRequestPublishResult publish(AiRequestOutboxRecord outboxRecord) {
        Message message = MessageBuilder.withBody(outboxRecord.messagePayload().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
        CorrelationData correlation = new CorrelationData(String.valueOf(outboxRecord.aiRequestId()));
        try {
            rabbitTemplate.send(AiRabbitTopology.REQUEST_EXCHANGE, outboxRecord.routingKey(), message, correlation);
        } catch (AmqpException e) {
            log.warn("AI 요청 메시지 발행에 실패했습니다. aiRequestId={}", outboxRecord.aiRequestId(), e);
            return AiRequestPublishResult.FAILED;
        }
        return awaitConfirm(correlation);
    }

    private AiRequestPublishResult awaitConfirm(CorrelationData correlation) {
        Confirm confirm;
        try {
            confirm = correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return AiRequestPublishResult.UNCONFIRMED;
        } catch (ExecutionException | TimeoutException e) {
            return AiRequestPublishResult.UNCONFIRMED;
        }
        if (!confirm.ack()) {
            return AiRequestPublishResult.NACKED;
        }
        if (correlation.getReturned() != null) {
            return AiRequestPublishResult.RETURNED;
        }
        return AiRequestPublishResult.CONFIRMED;
    }
}
