package com.backend.meety.global.config;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SchedulingConfig {

    public static final String DEFAULT_TASK_SCHEDULER_BEAN_NAME = "taskScheduler";
    public static final String CONNECTION_TASK_SCHEDULER_BEAN_NAME = "connectionTaskScheduler";
    public static final String RECORDING_STOP_RECONCILIATION_SCHEDULER_BEAN_NAME = "recordingStopTaskScheduler";

    @Bean(name = DEFAULT_TASK_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
        return builder.build();
    }

    @Bean(name = CONNECTION_TASK_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler connectionTaskScheduler(ConnectionSchedulerProperties properties) {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(properties.poolSize());
        scheduler.setThreadNamePrefix("connection-scheduling-");
        return scheduler;
    }

    @Bean(name = RECORDING_STOP_RECONCILIATION_SCHEDULER_BEAN_NAME)
    public ThreadPoolTaskScheduler recordingStopTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("recording-stop-reconciliation-");
        return scheduler;
    }
}
