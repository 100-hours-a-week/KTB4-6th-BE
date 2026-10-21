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
                    "chat.executor.queue-capacity=1000",
                    "meeting-sse.executor.core-pool-size=4",
                    "meeting-sse.executor.max-pool-size=4",
                    "meeting-sse.executor.queue-capacity=100",
                    "meeting-sse.executor.shutdown-await-seconds=10",
                    "transcript-save.executor.core-pool-size=4",
                    "transcript-save.executor.max-pool-size=4",
                    "transcript-save.executor.queue-capacity=100",
                    "transcript-save.executor.shutdown-await-seconds=20"
            );

    @Test
    @DisplayName("@Async가 이름으로 찾는 알림·챗봇 Executor를 각각 등록한다")
    void registersNotificationAndChatExecutors() {
        // 테스트 목적:
        // 회의 SSE 전송이 기존 알림·챗봇 작업과 별도 worker를 사용하고
        // 포화 시 호출 스레드에서 실행하지 않는 bounded Executor인지 검증한다.

        // given

        // when, then
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor chat =
                    context.getBean(AsyncConfig.CHAT_TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor notification =
                    context.getBean(AsyncConfig.NOTIFICATION_TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor meeting =
                    context.getBean(AsyncConfig.MEETING_SSE_TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);

            assertThat(chat.getCorePoolSize()).isEqualTo(8);
            assertThat(chat.getQueueCapacity()).isEqualTo(1000);
            assertThat(chat.getThreadNamePrefix()).isEqualTo("chat-");
            assertThat(notification.getThreadNamePrefix()).isEqualTo("notification-");
            assertThat(meeting).isNotSameAs(chat).isNotSameAs(notification);
            assertThat(meeting.getCorePoolSize()).isEqualTo(4);
            assertThat(meeting.getMaxPoolSize()).isEqualTo(4);
            assertThat(meeting.getQueueCapacity()).isEqualTo(100);
            assertThat(meeting.getThreadNamePrefix()).isEqualTo("meeting-sse-");
            assertThat(meeting.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
        });
    }

    @Test
    @DisplayName("전사 저장은 다른 작업과 별도의 bounded Executor를 사용한다")
    void registersTranscriptSaveExecutor() {
        // when, then
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            ThreadPoolTaskExecutor transcript =
                    context.getBean(AsyncConfig.TRANSCRIPT_SAVE_TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);
            ThreadPoolTaskExecutor meeting =
                    context.getBean(AsyncConfig.MEETING_SSE_TASK_EXECUTOR_BEAN_NAME, ThreadPoolTaskExecutor.class);

            assertThat(transcript).isNotSameAs(meeting);
            assertThat(transcript.getCorePoolSize()).isEqualTo(4);
            assertThat(transcript.getMaxPoolSize()).isEqualTo(4);
            assertThat(transcript.getQueueCapacity()).isEqualTo(100);
            assertThat(transcript.getThreadNamePrefix()).isEqualTo("transcript-save-");
            assertThat(transcript.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(java.util.concurrent.ThreadPoolExecutor.AbortPolicy.class);
        });
    }

    @Configuration
    @EnableConfigurationProperties({NotificationExecutorProperties.class, ChatExecutorProperties.class,
            MeetingSseExecutorProperties.class, TranscriptSaveExecutorProperties.class})
    static class PropertiesConfig {
    }
}
