package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.task.TaskSchedulingAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.Scheduled;

class SchedulingConfigTest {

    private static final long POLL_MILLIS = 20L;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(TaskSchedulingAutoConfiguration.class))
            .withUserConfiguration(SchedulingConfig.class, TestTasksConfig.class)
            .withPropertyValues(
                    "spring.task.scheduling.pool.size=2",
                    "connection-scheduler.pool-size=4"
            );

    @Test
    @DisplayName("기본 스케줄러 스레드가 모두 점유되어도 연결 유지 작업은 전용 스케줄러에서 계속 실행된다")
    void runsConnectionTasksWhileDefaultSchedulerIsBlocked() {
        // 테스트 목적:
        // AI 후처리 polling이 기본 스케줄러 2스레드를 점유해도
        // Owner 갱신 같은 연결 유지 작업이 밀리지 않는지 검증한다.

        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            TestTasks tasks = context.getBean(TestTasks.class);
            try {
                // given
                assertThat(tasks.defaultBlocked.await(2, TimeUnit.SECONDS)).isTrue();

                // when
                boolean connectionRan = tasks.connectionRuns.await(2, TimeUnit.SECONDS);

                // then
                assertThat(connectionRan).isTrue();
                assertThat(tasks.connectionThreads).allMatch(name -> name.startsWith("connection-scheduling-"));
                assertThat(tasks.defaultThreads).allMatch(name -> name.startsWith("scheduling-"));
            } finally {
                tasks.release.countDown();
            }
        });
    }

    @Configuration
    @EnableConfigurationProperties(ConnectionSchedulerProperties.class)
    static class TestTasksConfig {

        @Bean
        TestTasks testTasks() {
            return new TestTasks();
        }
    }

    static class TestTasks {

        private final CountDownLatch defaultBlocked = new CountDownLatch(2);
        private final CountDownLatch connectionRuns = new CountDownLatch(5);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicInteger blockedCount = new AtomicInteger();
        private final Set<String> defaultThreads = ConcurrentHashMap.newKeySet();
        private final Set<String> connectionThreads = ConcurrentHashMap.newKeySet();

        @Scheduled(fixedDelay = POLL_MILLIS)
        void firstPolling() throws InterruptedException {
            block();
        }

        @Scheduled(fixedDelay = POLL_MILLIS)
        void secondPolling() throws InterruptedException {
            block();
        }

        @Scheduled(fixedDelay = POLL_MILLIS, scheduler = SchedulingConfig.CONNECTION_TASK_SCHEDULER_BEAN_NAME)
        void keepConnection() {
            connectionThreads.add(Thread.currentThread().getName());
            connectionRuns.countDown();
        }

        private void block() throws InterruptedException {
            defaultThreads.add(Thread.currentThread().getName());
            if (blockedCount.incrementAndGet() <= 2) {
                defaultBlocked.countDown();
                release.await();
            }
        }
    }
}
