package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.PublishStatus;
import com.backend.meety.domain.ai.repository.AiRequestMySqlTestSupport;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@ContextConfiguration(classes = AiRequestPublishSchedulerIntegrationTest.PublishConfig.class)
class AiRequestPublishSchedulerIntegrationTest extends AiRequestMySqlTestSupport {

    static final LocalDateTime NOW = LocalDateTime.of(2026, 11, 14, 12, 0);
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
        registry.add("spring.rabbitmq.publisher-confirm-type", () -> "correlated");
        registry.add("spring.rabbitmq.publisher-returns", () -> "true");
        registry.add("spring.rabbitmq.template.mandatory", () -> "true");
        registry.add("ai.request.publish.batch-size", () -> "20");
        registry.add("ai.request.publish.confirm-timeout", () -> "5s");
    }

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    @EnableConfigurationProperties(AiRequestPublishProperties.class)
    @Import({AiRabbitTopologyConfig.class, AiRequestPublishService.class, AiRequestPublisher.class,
            AiRequestPublishScheduler.class})
    static class PublishConfig {

        @Bean
        Clock clock() {
            ZoneId zone = ZoneId.systemDefault();
            return Clock.fixed(NOW.atZone(zone).toInstant(), zone);
        }
    }

    @Autowired
    private AiRequestPublishScheduler scheduler;
    @Autowired
    private AiRequestPublishService publishService;
    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private RabbitAdmin rabbitAdmin;

    @AfterEach
    void purgeQueues() throws Exception {
        RABBIT.execInContainer("rabbitmqctl", "clear_policy", "reject-all");
        for (String queue : new String[] {AiRabbitTopology.SUMMARY_REQUEST_QUEUE, AiRabbitTopology.METRIC_REQUEST_QUEUE}) {
            rabbitAdmin.purgeQueue(queue);
        }
    }

    @Test
    @DisplayName("발행이 확인되면 PUBLISHED와 발행 시각을 기록하고 요청 Queue에 payload를 그대로 전달한다")
    void publishesClaimedRequest() {
        long requestId = createPublishable("summary");

        scheduler.publishPending();

        AiRequest request = findRequest(requestId);
        assertThat(request.getPublishStatus()).isEqualTo(PublishStatus.PUBLISHED);
        assertThat(request.getPublishedAt()).isEqualTo(NOW);
        Message message = rabbitTemplate.receive(AiRabbitTopology.SUMMARY_REQUEST_QUEUE, 5_000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).isEqualTo(request.getMessagePayload());
        assertThat(message.getMessageProperties().getContentType()).isEqualTo(MessageProperties.CONTENT_TYPE_JSON);
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
    }

    @Test
    @DisplayName("라우팅할 Queue가 없어 Return되면 UNPUBLISHED로 되돌리고 재시도 횟수는 그대로 둔다")
    void revertsReturnedRequest() {
        long requestId = createPublishable("report");

        scheduler.publishPending();

        AiRequest request = findRequest(requestId);
        assertThat(request.getPublishStatus()).isEqualTo(PublishStatus.UNPUBLISHED);
        assertThat(request.getPublishedAt()).isNull();
        assertThat(request.getRetryCount()).isZero();
        assertThat(publishService.findPublishableIds(20)).contains(requestId);
    }

    @Test
    @DisplayName("브로커가 Publisher NACK으로 거절하면 UNPUBLISHED로 되돌린다")
    void revertsNackedRequest() throws Exception {
        RABBIT.execInContainer("rabbitmqctl", "set_policy", "reject-all",
                "^" + AiRabbitTopology.METRIC_REQUEST_QUEUE.replace(".", "\\.") + "$",
                "{\"max-length\": 1}", "--apply-to", "queues");
        awaitPolicyApplied();
        rabbitTemplate.convertAndSend(AiRabbitTopology.REQUEST_EXCHANGE, AiRabbitTopology.METRIC_ROUTING_KEY, "filler-1");
        rabbitTemplate.convertAndSend(AiRabbitTopology.REQUEST_EXCHANGE, AiRabbitTopology.METRIC_ROUTING_KEY, "filler-2");
        awaitMessageCount(AiRabbitTopology.METRIC_REQUEST_QUEUE, 2);
        long requestId = createPublishable("metric");

        scheduler.publishPending();

        AiRequest request = findRequest(requestId);
        assertThat(request.getPublishStatus()).isEqualTo(PublishStatus.UNPUBLISHED);
        assertThat(request.getRetryCount()).isZero();
    }

    @Test
    @DisplayName("다른 processingToken으로는 발행 결과를 반영하지 않는다")
    void ignoresResultForStaleToken() {
        long requestId = createPublishable("summary");
        assertThat(publishService.claim(requestId)).isPresent();

        assertThat(publishService.markPublished(requestId, "old-token")).isFalse();
        assertThat(publishService.revertToUnpublished(requestId, "old-token")).isFalse();

        assertThat(findRequest(requestId).getPublishStatus()).isEqualTo(PublishStatus.PUBLISHING);
    }

    @Test
    @DisplayName("PUBLISHING이 아닌 요청에는 발행 결과를 반영하지 않는다")
    void ignoresResultForUnclaimedRequest() {
        long requestId = createPublishable("summary");

        assertThat(publishService.markPublished(requestId, "token-" + requestId)).isFalse();

        assertThat(findRequest(requestId).getPublishStatus()).isEqualTo(PublishStatus.UNPUBLISHED);
    }

    private long createPublishable(String routingKey) {
        long requestId = createRequest(AiRequestType.SUMMARY);
        jdbc.update("""
                        update ai_requests
                        set publish_status = 'UNPUBLISHED', processing_token = ?,
                            message_payload = ?, publish_routing_key = ?, next_publish_at = ?
                        where id = ?
                        """,
                "token-" + requestId, "{\"schemaVersion\": 1, \"aiRequestId\": " + requestId + "}",
                routingKey, NOW, requestId);
        return requestId;
    }

    private void awaitMessageCount(String queue, int expected) throws InterruptedException {
        for (int attempt = 0; attempt < 50; attempt++) {
            if (rabbitAdmin.getQueueInfo(queue).getMessageCount() == expected) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("queue did not reach " + expected + " messages");
    }

    private void awaitPolicyApplied() throws Exception {
        for (int attempt = 0; attempt < 50; attempt++) {
            String policies = RABBIT.execInContainer("rabbitmqctl", "list_queues", "name", "policy", "--quiet")
                    .getStdout();
            if (policies.contains("reject-all")) {
                return;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("policy was not applied");
    }
}
