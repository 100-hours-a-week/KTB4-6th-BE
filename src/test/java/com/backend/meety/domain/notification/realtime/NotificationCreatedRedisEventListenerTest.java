package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.event.TransactionalEventListenerFactory;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class NotificationCreatedRedisEventListenerTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(NotificationCreatedRedisEventListenerTestConfig.class);

    @Test
    @DisplayName("알림 생성 이벤트를 Redis Publisher에 전달한다")
    void publishNotificationCreatedDelegatesToRedisPublisher() {
        // 테스트 목적:
        // Notification 저장 성공 후 발행된 이벤트가
        // 로컬 SSE 직접 전송이 아닌 Redis publish 계층으로 전달되는지 검증한다.

        // given
        NotificationRedisPublisher notificationRedisPublisher = mock(NotificationRedisPublisher.class);
        NotificationCreatedRedisEventListener listener =
                new NotificationCreatedRedisEventListener(notificationRedisPublisher);
        NotificationCreatedEvent event = notificationCreatedEvent();

        // when
        listener.publishNotificationCreated(event);

        // then
        verify(notificationRedisPublisher).publish(event);
    }

    @Test
    @DisplayName("알림 생성 Redis listener는 커밋 이후 실행된다")
    void listenerRunsAfterCommit() throws NoSuchMethodException {
        // 테스트 목적:
        // NotificationWriter의 REQUIRES_NEW 저장 트랜잭션이 커밋된 뒤에만
        // Redis publish listener가 실행되도록 트랜잭션 이벤트 phase를 검증한다.

        // given
        Method method = NotificationCreatedRedisEventListener.class
                .getMethod("publishNotificationCreated", NotificationCreatedEvent.class);

        // when
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("커밋 이후 NotificationCreatedEvent는 Redis publish를 실행한다")
    void eventAfterCommitPublishesToRedis() {
        // 테스트 목적:
        // NotificationCreatedEvent가 트랜잭션 안에서 발행되면
        // 트랜잭션 커밋 이후 Redis Publisher가 호출되는지 검증한다.

        contextRunner.run(context -> {
            // given
            NotificationRedisPublisher notificationRedisPublisher = context.getBean(NotificationRedisPublisher.class);
            TransactionTemplate transactionTemplate =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            NotificationCreatedEvent event = notificationCreatedEvent();

            // when
            transactionTemplate.execute(status -> {
                context.publishEvent(event);
                return null;
            });

            // then
            verify(notificationRedisPublisher).publish(event);
        });
    }

    @Test
    @DisplayName("트랜잭션 rollback 시 Redis publish를 실행하지 않는다")
    void eventRollbackDoesNotPublishToRedis() {
        // 테스트 목적:
        // Notification 저장 트랜잭션이 rollback되면
        // 저장되지 않은 알림이 Redis로 publish되지 않는지 검증한다.

        contextRunner.run(context -> {
            // given
            NotificationRedisPublisher notificationRedisPublisher = context.getBean(NotificationRedisPublisher.class);
            TransactionTemplate transactionTemplate =
                    new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
            NotificationCreatedEvent event = notificationCreatedEvent();

            // when
            transactionTemplate.execute(status -> {
                context.publishEvent(event);
                status.setRollbackOnly();
                return null;
            });

            // then
            verify(notificationRedisPublisher, never()).publish(any(NotificationCreatedEvent.class));
        });
    }

    @Test
    @DisplayName("Redis publish 실패는 알림 생성 이벤트 처리를 실패시키지 않는다")
    void redisPublishFailureDoesNotPropagate() {
        // 테스트 목적:
        // 알림은 이미 DB에 저장된 상태이므로 Redis publish 계층에서 오류가 나도
        // 이벤트 listener 밖으로 예외를 전파하지 않는지 검증한다.

        // given
        NotificationRedisPublisher notificationRedisPublisher = mock(NotificationRedisPublisher.class);
        NotificationCreatedEvent event = notificationCreatedEvent();
        doThrow(new IllegalStateException("redis failed"))
                .when(notificationRedisPublisher).publish(event);
        NotificationCreatedRedisEventListener listener =
                new NotificationCreatedRedisEventListener(notificationRedisPublisher);

        // when, then
        assertThatCode(() -> listener.publishNotificationCreated(event))
                .doesNotThrowAnyException();
    }

    private NotificationCreatedEvent notificationCreatedEvent() {
        return new NotificationCreatedEvent(
                15L,
                1L,
                NotificationType.MEMBER_JOINED,
                "test2 님이 팀에 합류했습니다",
                NotificationReferenceType.TEAM,
                null,
                false,
                LocalDateTime.of(2026, 10, 9, 13, 30, 12)
        );
    }

    @Configuration(proxyBeanMethods = false)
    static class NotificationCreatedRedisEventListenerTestConfig {

        @Bean
        NotificationRedisPublisher notificationRedisPublisher() {
            return Mockito.mock(NotificationRedisPublisher.class);
        }

        @Bean
        NotificationCreatedRedisEventListener notificationCreatedRedisEventListener(
                NotificationRedisPublisher notificationRedisPublisher
        ) {
            return new NotificationCreatedRedisEventListener(notificationRedisPublisher);
        }

        @Bean
        PlatformTransactionManager transactionManager() {
            return new TestTransactionManager();
        }

        @Bean
        TransactionalEventListenerFactory transactionalEventListenerFactory() {
            return new TransactionalEventListenerFactory();
        }
    }

    private static class TestTransactionManager extends AbstractPlatformTransactionManager {

        @Override
        protected Object doGetTransaction() {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status) {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status) {
        }
    }
}
