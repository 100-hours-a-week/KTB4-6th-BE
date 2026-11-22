package com.backend.meety.domain.ai.messaging;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class AiResponseConsumerControl {

    private static final int DATABASE_VALIDATION_TIMEOUT_SECONDS = 2;

    private final RabbitListenerEndpointRegistry listenerRegistry;
    private final DataSource dataSource;
    private final AtomicBoolean stoppedByDatabaseFailure = new AtomicBoolean(false);

    public void stopForDatabaseFailure() {
        if (!stoppedByDatabaseFailure.compareAndSet(false, true)) {
            return;
        }
        log.error("DB 저장 실패가 계속되어 AI 응답 Consumer 수신을 중단합니다.");
        CompletableFuture.runAsync(listenerContainer()::stop);
    }

    @Scheduled(fixedDelayString = "${ai.response.resume-check-delay}")
    public void resumeIfDatabaseAvailable() {
        if (!stoppedByDatabaseFailure.get() || !isDatabaseAvailable()) {
            return;
        }
        MessageListenerContainer container = listenerContainer();
        if (container.isRunning()) {
            return;
        }
        container.start();
        stoppedByDatabaseFailure.set(false);
        log.info("DB 연결이 회복되어 AI 응답 Consumer 수신을 재개합니다.");
    }

    private boolean isDatabaseAvailable() {
        try (Connection connection = dataSource.getConnection()) {
            return connection.isValid(DATABASE_VALIDATION_TIMEOUT_SECONDS);
        } catch (SQLException e) {
            return false;
        }
    }

    private MessageListenerContainer listenerContainer() {
        return listenerRegistry.getListenerContainer(AiResponseConsumer.LISTENER_ID);
    }
}
