package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AsyncConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AsyncConfig.class, PropertiesConfig.class)
            .withPropertyValues(
                    "notification.executor.core-pool-size=4",
                    "notification.executor.max-pool-size=4",
                    "notification.executor.queue-capacity=100",
                    "chat.executor.core-pool-size=8",
                    "chat.executor.max-pool-size=8",
                    "chat.executor.queue-capacity=1000"
            );

    @Test
    @DisplayName("@Async가 이름으로 찾는 알림·챗봇 Executor를 각각 등록한다")
    void registersNotificationAndChatExecutors() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor chat =
                    context.getBean(AsyncConfig.CHAT_TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor notification =
                    context.getBean(AsyncConfig.NOTIFICATION_TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);

            assertThat(chat.getCorePoolSize()).isEqualTo(8);
            assertThat(chat.getQueueCapacity()).isEqualTo(1000);
            assertThat(chat.getThreadNamePrefix()).isEqualTo("chat-");
            assertThat(notification.getThreadNamePrefix()).isEqualTo("notification-");
        });
    }

    @Configuration
    @EnableConfigurationProperties({NotificationExecutorProperties.class, ChatExecutorProperties.class})
    static class PropertiesConfig {
    }
}
