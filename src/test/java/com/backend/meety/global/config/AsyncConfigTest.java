package com.backend.meety.global.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class AsyncConfigTest {

    @Test
    @DisplayName("Notification 전용 Executor는 설정값으로 고정 Worker와 Queue를 구성한다")
    void notificationTaskExecutorUsesConfiguredPool() {
        // 테스트 목적:
        // Notification 부하테스트에서 Worker 수만 독립 변수로 조정할 수 있도록
        // 전용 Executor가 설정값의 core, max, queue capacity를 그대로 사용하는지 검증한다.

        // given
        AsyncConfig config = new AsyncConfig();
        NotificationExecutorProperties properties = new NotificationExecutorProperties(4, 4, 100);

        // when
        ThreadPoolTaskExecutor executor = config.notificationTaskExecutor(properties);
        executor.initialize();

        try {
            // then
            assertThat(executor.getCorePoolSize()).isEqualTo(4);
            assertThat(executor.getMaxPoolSize()).isEqualTo(4);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("notification-");
            assertThat(executor.getThreadPoolExecutor().getQueue().remainingCapacity()).isEqualTo(100);
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        } finally {
            executor.shutdown();
        }
    }
}
