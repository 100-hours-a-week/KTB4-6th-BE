package com.backend.meety.global.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String NOTIFICATION_TASK_EXECUTOR_BEAN_NAME = "notificationTaskExecutor";
    public static final String CHAT_TASK_EXECUTOR_BEAN_NAME = "chatTaskExecutor";
    public static final String MEETING_SSE_TASK_EXECUTOR_BEAN_NAME = "meetingSseTaskExecutor";
    public static final String TRANSCRIPT_SAVE_TASK_EXECUTOR_BEAN_NAME = "transcriptSaveTaskExecutor";

    @Bean(name = NOTIFICATION_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor notificationTaskExecutor(NotificationExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("notification-");
        return executor;
    }

    @Bean(name = CHAT_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor chatTaskExecutor(ChatExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("chat-");
        return executor;
    }

    @Bean(name = MEETING_SSE_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor meetingSseTaskExecutor(MeetingSseExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("meeting-sse-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.shutdownAwaitSeconds());
        return executor;
    }

    @Bean(name = TRANSCRIPT_SAVE_TASK_EXECUTOR_BEAN_NAME)
    public ThreadPoolTaskExecutor transcriptSaveTaskExecutor(TranscriptSaveExecutorProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("transcript-save-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(properties.shutdownAwaitSeconds());
        return executor;
    }
}
