package com.backend.meety.domain.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.global.config.AsyncConfig;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class ChatCancelListenerTest {

    @Test
    @DisplayName("회의 종료 커밋 후 챗 취소는 챗 Executor에서 실행되어 커밋한 스레드를 붙잡지 않는다")
    void cancelsChatsOnChatExecutorWithoutHoldingCommittingThread() throws Exception {
        // given
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext(TestConfig.class)) {
            ChatProcessingService chatProcessingService = context.getBean(ChatProcessingService.class);
            CountDownLatch entered = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            AtomicReference<String> cancelThread = new AtomicReference<>();
            doAnswer(call -> {
                cancelThread.set(Thread.currentThread().getName());
                entered.countDown();
                release.await(3, TimeUnit.SECONDS);
                return null;
            }).when(chatProcessingService).cancelProcessing(42L);
            TransactionTemplate transaction = new TransactionTemplate(context.getBean(NoOpTransactionManager.class));
            ApplicationEventPublisher publisher = context;

            // when
            transaction.executeWithoutResult(status -> publisher.publishEvent(new MeetingCompletedEvent(42L)));

            // then
            assertThat(entered.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(cancelThread.get()).startsWith("chat-").isNotEqualTo(Thread.currentThread().getName());
            release.countDown();
        }
    }

    @Configuration
    @EnableAsync
    @EnableTransactionManagement
    @Import(ChatCancelListener.class)
    static class TestConfig {

        @Bean
        ChatProcessingService chatProcessingService() {
            return mock(ChatProcessingService.class);
        }

        @Bean
        NoOpTransactionManager transactionManager() {
            return new NoOpTransactionManager();
        }

        @Bean(name = AsyncConfig.CHAT_TASK_EXECUTOR_BEAN_NAME)
        ThreadPoolTaskExecutor chatTaskExecutor() {
            ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
            executor.setCorePoolSize(1);
            executor.setMaxPoolSize(1);
            executor.setThreadNamePrefix("chat-");
            return executor;
        }
    }

    private static class NoOpTransactionManager extends AbstractPlatformTransactionManager {

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
