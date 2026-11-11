package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.amqp.autoconfigure.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.rabbitmq.RabbitMQContainer;

@Testcontainers
@SpringBootTest(classes = RabbitConnectionIntegrationTest.TestConfig.class)
class RabbitConnectionIntegrationTest {

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbit = new RabbitMQContainer("rabbitmq:4.1-management");

    @Configuration(proxyBeanMethods = false)
    @ImportAutoConfiguration(RabbitAutoConfiguration.class)
    static class TestConfig {
    }

    @Autowired
    private CachingConnectionFactory connectionFactory;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Test
    @DisplayName("Publisher Confirm과 Return이 활성화된 상태로 브로커에 연결한다")
    void connectsWithPublisherConfirmAndReturns() {
        assertThat(connectionFactory.isPublisherConfirms()).isTrue();
        assertThat(connectionFactory.isPublisherReturns()).isTrue();
        assertThat(connectionFactory.createConnection().isOpen()).isTrue();
    }

    @Test
    @DisplayName("존재하는 Queue로 발행한 메시지는 Confirm ACK를 받는다")
    void receivesConfirmAckForRoutedMessage() throws Exception {
        String queueName = rabbitAdmin.declareQueue(new Queue("", false, true, true));
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());

        rabbitTemplate.convertAndSend("", queueName, "ping", correlation);

        assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).ack()).isTrue();
        assertThat(rabbitTemplate.receiveAndConvert(queueName, 5_000)).isEqualTo("ping");
    }

    @Test
    @DisplayName("라우팅되지 않은 mandatory 메시지는 Return으로 돌아온다")
    void returnsUnroutableMandatoryMessage() throws Exception {
        CompletableFuture<ReturnedMessage> returned = new CompletableFuture<>();
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setReturnsCallback(returned::complete);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());

        template.convertAndSend("", "missing-" + UUID.randomUUID(), "ping", correlation);

        assertThat(returned.get(5, TimeUnit.SECONDS).getReplyCode()).isEqualTo(312);
        assertThat(correlation.getFuture().get(5, TimeUnit.SECONDS).ack()).isTrue();
    }
}
