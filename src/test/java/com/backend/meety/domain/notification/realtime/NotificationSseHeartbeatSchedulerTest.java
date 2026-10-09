package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

class NotificationSseHeartbeatSchedulerTest {

    @Test
    @DisplayName("heartbeat 스케줄러는 공통 서비스 실행 메서드를 호출한다")
    void sendHeartbeatDelegatesToService() {
        // 테스트 목적:
        // emitter마다 별도 스레드를 만들지 않고
        // 공통 스케줄러 실행이 현재 Registry 순회 서비스로 위임되는지 검증한다.

        // given
        NotificationSseService notificationSseService = mock(NotificationSseService.class);
        NotificationSseHeartbeatScheduler scheduler = new NotificationSseHeartbeatScheduler(notificationSseService);

        // when
        scheduler.sendHeartbeat();

        // then
        verify(notificationSseService).sendHeartbeat();
    }

    @Test
    @DisplayName("heartbeat 주기는 30초로 설정한다")
    void heartbeatIntervalIsThirtySeconds() throws NoSuchMethodException {
        // 테스트 목적:
        // Nginx/LB idle timeout보다 충분히 짧은 30초 주기로
        // 서버 주도 heartbeat가 실행되도록 스케줄 설정을 검증한다.

        // given
        Method method = NotificationSseHeartbeatScheduler.class.getMethod("sendHeartbeat");

        // when
        Scheduled scheduled = method.getAnnotation(Scheduled.class);

        // then
        assertThat(scheduled).isNotNull();
        assertThat(scheduled.fixedRate()).isEqualTo(30_000L);
    }
}
