package com.backend.meety.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String NOTIFICATION_TASK_EXECUTOR_BEAN_NAME = "notificationTaskExecutor";

    @Bean(name = NOTIFICATION_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor notificationTaskExecutor(NotificationExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("notification-");
        return executor;
    }
}
