package com.backend.meety.domain.ai.messaging;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiResponseConsumer {

    private static final String MANUAL_ACK_MODE = "MANUAL";

    private final AiResponseMessageParser parser;
    private final AiResponseService responseService;

    @RabbitListener(queues = AiRabbitTopology.RESPONSE_QUEUE, ackMode = MANUAL_ACK_MODE)
    public void consume(Message message, Channel channel) throws IOException {
        long deliveryTag = message.getMessageProperties().getDeliveryTag();
        AiResponseMessage response = parser.parse(message.getBody()).orElse(null);
        if (response == null) {
            log.error("형식이 잘못된 AI 응답을 DLQ로 격리합니다. deliveryTag={}", deliveryTag);
            channel.basicNack(deliveryTag, false, false);
            return;
        }
        responseService.handle(response);
        channel.basicAck(deliveryTag, false);
    }
}
