package com.backend.meety.domain.ai.messaging;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionException;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiResponseConsumer {

    public static final String LISTENER_ID = "aiResponseConsumer";

    private static final String MANUAL_ACK_MODE = "MANUAL";

    private final AiResponseMessageParser parser;
    private final AiResponseService responseService;
    private final AiResponseConsumerControl consumerControl;
    private final AiResponseProperties properties;

    @RabbitListener(id = LISTENER_ID, queues = AiRabbitTopology.RESPONSE_QUEUE, ackMode = MANUAL_ACK_MODE)
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        AiResponseMessage response = parser.parse(message.getBody()).orElse(null);
        if (response == null) {
            log.error("형식이 잘못된 AI 응답을 DLQ로 격리합니다. deliveryTag={}", deliveryTag);
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        if (!handleWithRetry(response)) {
            consumerControl.stopForDatabaseFailure();
            return;
        }
        channel.basicAck(deliveryTag, false);
    }

    private boolean handleWithRetry(AiResponseMessage response) {
        for (int attempt = 1; attempt <= properties.saveMaxAttempts(); attempt++) {
            try {
                responseService.handle(response);
                return true;
            } catch (DataAccessException | TransactionException e) {
                log.warn("AI 응답 저장에 실패했습니다. aiRequestId={}, attempt={}/{}",
                        response.aiRequestId(), attempt, properties.saveMaxAttempts(), e);
                if (attempt < properties.saveMaxAttempts() && !sleep()) {
                    return false;
                }
            }
        }
        return false;
    }

    private boolean sleep() {
        try {
            Thread.sleep(properties.saveRetryInterval());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
