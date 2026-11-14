package com.backend.meety.domain.ai.messaging;

import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.CHAT_REQUEST_QUEUE;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.CHAT_ROUTING_KEY;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.DEAD_LETTER_ROUTING_KEY;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.DIARIZATION_REQUEST_QUEUE;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.DIARIZATION_ROUTING_KEY;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.METRIC_REQUEST_QUEUE;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.METRIC_ROUTING_KEY;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.REQUEST_EXCHANGE;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.RESPONSE_DLQ;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.RESPONSE_DLX;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.RESPONSE_EXCHANGE;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.RESPONSE_QUEUE;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.RESPONSE_ROUTING_KEY;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.SPEAKER_EMBEDDING_REQUEST_QUEUE;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.SPEAKER_EMBEDDING_ROUTING_KEY;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.SUMMARY_REQUEST_QUEUE;
import static com.backend.meety.domain.ai.messaging.AiRabbitTopology.SUMMARY_ROUTING_KEY;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueBuilder.Overflow;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AiRabbitTopologyConfig {

    @Bean
    public Declarables aiRequestTopology() {
        DirectExchange exchange = new DirectExchange(REQUEST_EXCHANGE, true, false);
        Queue summary = quorumQueue(SUMMARY_REQUEST_QUEUE).build();
        Queue diarization = quorumQueue(DIARIZATION_REQUEST_QUEUE).build();
        Queue metric = quorumQueue(METRIC_REQUEST_QUEUE).build();
        Queue chat = quorumQueue(CHAT_REQUEST_QUEUE).build();
        Queue speakerEmbedding = quorumQueue(SPEAKER_EMBEDDING_REQUEST_QUEUE).build();
        return new Declarables(
                exchange,
                summary, diarization, metric, chat, speakerEmbedding,
                bind(summary, exchange, SUMMARY_ROUTING_KEY),
                bind(diarization, exchange, DIARIZATION_ROUTING_KEY),
                bind(metric, exchange, METRIC_ROUTING_KEY),
                bind(chat, exchange, CHAT_ROUTING_KEY),
                bind(speakerEmbedding, exchange, SPEAKER_EMBEDDING_ROUTING_KEY)
        );
    }

    @Bean
    public Declarables aiResponseTopology() {
        DirectExchange exchange = new DirectExchange(RESPONSE_EXCHANGE, true, false);
        DirectExchange deadLetterExchange = new DirectExchange(RESPONSE_DLX, true, false);
        Queue response = quorumQueue(RESPONSE_QUEUE)
                .deadLetterExchange(RESPONSE_DLX)
                .deadLetterRoutingKey(DEAD_LETTER_ROUTING_KEY)
                .withArgument("x-dead-letter-strategy", "at-least-once")
                .build();
        Queue deadLetter = quorumQueue(RESPONSE_DLQ).build();
        return new Declarables(
                exchange, deadLetterExchange,
                response, deadLetter,
                bind(response, exchange, RESPONSE_ROUTING_KEY),
                bind(deadLetter, deadLetterExchange, DEAD_LETTER_ROUTING_KEY)
        );
    }

    private QueueBuilder quorumQueue(String name) {
        return QueueBuilder.durable(name)
                .quorum()
                .overflow(Overflow.rejectPublish);
    }

    private Binding bind(Queue queue, DirectExchange exchange, String routingKey) {
        return BindingBuilder.bind(queue).to(exchange).with(routingKey);
    }
}
