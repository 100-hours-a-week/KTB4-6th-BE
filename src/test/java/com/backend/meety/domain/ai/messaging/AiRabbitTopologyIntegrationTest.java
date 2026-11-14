package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.rabbitmq.client.GetResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
@SpringBootTest(classes = AiRabbitTopologyIntegrationTest.TestConfig.class)
class AiRabbitTopologyIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management");

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @Import(AiRabbitTopologyConfig.class)
    static class TestConfig {
    }

    private static final List<String> ALL_QUEUES = List.of(
            AiRabbitTopology.SUMMARY_REQUEST_QUEUE,
            AiRabbitTopology.DIARIZATION_REQUEST_QUEUE,
            AiRabbitTopology.METRIC_REQUEST_QUEUE,
            AiRabbitTopology.CHAT_REQUEST_QUEUE,
            AiRabbitTopology.SPEAKER_EMBEDDING_REQUEST_QUEUE,
            AiRabbitTopology.RESPONSE_QUEUE,
            AiRabbitTopology.RESPONSE_DLQ
    );

    @Autowired
    private CachingConnectionFactory connectionFactory;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @BeforeEach
    void connect() {
        connectionFactory.createConnection().close();
    }

    @Test
    @DisplayName("Queue 7개를 Quorum 타입과 reject-publish로 선언한다")
    void declaresQuorumQueues() throws Exception {
        Map<String, DeclaredQueue> queues = listQueues();

        assertThat(queues).containsOnlyKeys(ALL_QUEUES);
        assertThat(queues.values()).allSatisfy(queue -> {
            assertThat(queue.type()).isEqualTo("quorum");
            assertThat(queue.durable()).isTrue();
            assertThat(queue.autoDelete()).isFalse();
            assertThat(queue.arguments()).containsEntry("x-overflow", "reject-publish")
                    .doesNotContainKeys("x-max-length", "x-consumer-timeout", "x-message-ttl");
        });
    }

    @Test
    @DisplayName("공통 응답 Queue에만 at-least-once Dead Lettering을 설정한다")
    void configuresDeadLetteringOnResponseQueue() throws Exception {
        Map<String, DeclaredQueue> queues = listQueues();

        assertThat(queues.get(AiRabbitTopology.RESPONSE_QUEUE).arguments())
                .containsEntry("x-dead-letter-exchange", AiRabbitTopology.RESPONSE_DLX)
                .containsEntry("x-dead-letter-routing-key", AiRabbitTopology.DEAD_LETTER_ROUTING_KEY)
                .containsEntry("x-dead-letter-strategy", "at-least-once");
        assertThat(queues).allSatisfy((name, queue) -> {
            if (!name.equals(AiRabbitTopology.RESPONSE_QUEUE)) {
                assertThat(queue.arguments()).doesNotContainKey("x-dead-letter-exchange");
            }
        });
    }

    @ParameterizedTest
    @CsvSource({
            "meety.ai.request, summary, meety.ai.summary.request",
            "meety.ai.request, diarization, meety.ai.diarization.request",
            "meety.ai.request, metric, meety.ai.metric.request",
            "meety.ai.request, chat, meety.ai.chat.request",
            "meety.ai.request, speaker-embedding, meety.ai.speaker-embedding.request",
            "meety.ai.response, event, meety.ai.response.events"
    })
    @DisplayName("Routing Key별로 해당 Queue에만 전달한다")
    void routesToMatchingQueueOnly(String exchange, String routingKey, String queue) {
        String body = UUID.randomUUID().toString();

        rabbitTemplate.convertAndSend(exchange, routingKey, body);

        assertThat(rabbitTemplate.receiveAndConvert(queue, 5_000)).isEqualTo(body);
        assertThat(ALL_QUEUES).allSatisfy(name ->
                assertThat(rabbitAdmin.getQueueInfo(name).getMessageCount()).isZero());
    }

    @Test
    @DisplayName("요청 Exchange에 없는 Routing Key로 발행하면 Return으로 돌아온다")
    void returnsUnknownRequestRoutingKey() throws Exception {
        CompletableFuture<ReturnedMessage> returned = new CompletableFuture<>();
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setReturnsCallback(returned::complete);

        template.convertAndSend(AiRabbitTopology.REQUEST_EXCHANGE, "report", "ping");

        assertThat(returned.get(5, TimeUnit.SECONDS).getReplyCode()).isEqualTo(312);
    }

    @Test
    @DisplayName("공통 응답 Queue에서 requeue 없이 거절한 메시지는 DLQ로 이동한다")
    void deadLettersRejectedResponse() {
        String body = UUID.randomUUID().toString();
        rabbitTemplate.convertAndSend(AiRabbitTopology.RESPONSE_EXCHANGE, AiRabbitTopology.RESPONSE_ROUTING_KEY, body);

        rabbitTemplate.execute(channel -> {
            GetResponse response = null;
            for (int attempt = 0; response == null && attempt < 50; attempt++) {
                response = channel.basicGet(AiRabbitTopology.RESPONSE_QUEUE, false);
                if (response == null) {
                    Thread.sleep(100);
                }
            }
            assertThat(response).isNotNull();
            channel.basicNack(response.getEnvelope().getDeliveryTag(), false, false);
            return null;
        });

        assertThat(rabbitTemplate.receiveAndConvert(AiRabbitTopology.RESPONSE_DLQ, 5_000)).isEqualTo(body);
    }

    @Test
    @DisplayName("같은 선언을 다시 적용해도 실패하지 않는다")
    void redeclaresWithoutConflict() {
        assertThatCode(rabbitAdmin::initialize).doesNotThrowAnyException();
    }

    private Map<String, DeclaredQueue> listQueues() throws Exception {
        var result = rabbit.execInContainer(
                "rabbitmqctl", "list_queues", "name", "type", "durable", "auto_delete", "arguments",
                "--formatter", "json", "--quiet");
        Map<String, DeclaredQueue> queues = new HashMap<>();
        for (JsonNode queue : new ObjectMapper().readTree(result.getStdout())) {
            Map<String, String> arguments = new HashMap<>();
            queue.get("arguments").forEach(argument -> arguments.put(argument.get(0).asText(), argument.get(2).asText()));
            queues.put(queue.get("name").asText(), new DeclaredQueue(
                    queue.get("type").asText(),
                    queue.get("durable").asBoolean(),
                    queue.get("auto_delete").asBoolean(),
                    arguments));
        }
        return queues;
    }

    private record DeclaredQueue(String type, boolean durable, boolean autoDelete, Map<String, String> arguments) {
    }
}
