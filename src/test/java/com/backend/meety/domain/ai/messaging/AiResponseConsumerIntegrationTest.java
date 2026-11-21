package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestMySqlTestSupport;
import java.time.Clock;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@ContextConfiguration(classes = AiResponseConsumerIntegrationTest.ConsumerConfig.class)
class AiResponseConsumerIntegrationTest extends AiRequestMySqlTestSupport {

    static final RabbitMQContainer RABBIT = new RabbitMQContainer("rabbitmq:4.1-management");

    static {
        RABBIT.start();
    }

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RABBIT::getHost);
        registry.add("spring.rabbitmq.port", RABBIT::getAmqpPort);
        registry.add("spring.rabbitmq.username", RABBIT::getAdminUsername);
        registry.add("spring.rabbitmq.password", RABBIT::getAdminPassword);
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @Import({AiRabbitTopologyConfig.class, AiResponseConsumer.class, AiResponseService.class,
            AiResponseMessageParser.class})
    static class ConsumerConfig {

        @Bean
        Clock clock() {
            return Clock.systemDefaultZone();
        }

        @Bean
        ObjectMapper objectMapper() {
            return new ObjectMapper();
        }

        @Bean
        AiResultHandler summaryHandler() {
            return new AiResultHandler() {
                @Override
                public AiRequestType requestType() {
                    return AiRequestType.SUMMARY;
                }

                @Override
                public void handleSucceeded(Long aiRequestId, JsonNode result) {
                }

                @Override
                public void handleFinalFailure(Long aiRequestId) {
                }
            };
        }
    }

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;

    @AfterEach
    void purgeQueues() {
        rabbitAdmin.purgeQueue(AiRabbitTopology.RESPONSE_QUEUE);
        rabbitAdmin.purgeQueue(AiRabbitTopology.RESPONSE_DLQ);
    }

    @Test
    @DisplayName("정상 성공 응답은 DB에 반영한 뒤 ACK해 응답 Queue에 남지 않는다")
    void appliesAndAcknowledgesValidResponse() {
        long requestId = createPublished();

        publishResponse("""
                {"schemaVersion":1,"aiRequestId":%d,"processingToken":"token-1","requestType":"SUMMARY",
                 "eventType":"SUCCEEDED","result":{"content":"요약"}}
                """.formatted(requestId));

        await(() -> findRequest(requestId).getStatus() == AiRequestStatus.COMPLETED);
        await(() -> messageCount(AiRabbitTopology.RESPONSE_QUEUE) == 0);
        assertThat(messageCount(AiRabbitTopology.RESPONSE_DLQ)).isZero();
    }

    @Test
    @DisplayName("형식이 잘못된 응답은 DLQ로 격리하고 작업 상태는 바꾸지 않는다")
    void deadLettersInvalidResponse() {
        long requestId = createPublished();

        publishResponse("""
                {"schemaVersion":1,"aiRequestId":%d,"processingToken":"token-1","requestType":"SUMMARY",
                 "eventType":"SUCCEEDED"}
                """.formatted(requestId));

        await(() -> messageCount(AiRabbitTopology.RESPONSE_DLQ) == 1);
        assertThat(findRequest(requestId).getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
    }

    private long createPublished() {
        long requestId = createRequest(AiRequestType.SUMMARY);
        jdbc.update("""
                        update ai_requests
                        set publish_status = 'PUBLISHED', processing_token = 'token-1',
                            message_payload = '{}', publish_routing_key = 'summary'
                        where id = ?
                        """, requestId);
        return requestId;
    }

    private void publishResponse(String body) {
        rabbitTemplate.convertAndSend(AiRabbitTopology.RESPONSE_EXCHANGE, AiRabbitTopology.RESPONSE_ROUTING_KEY, body);
    }

    private long messageCount(String queue) {
        return rabbitAdmin.getQueueInfo(queue).getMessageCount();
    }

    private void await(BooleanSupplier condition) {
        for (int attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("condition was not met within 10 seconds");
    }
}
