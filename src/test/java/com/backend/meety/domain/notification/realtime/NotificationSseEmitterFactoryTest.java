package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseEmitterFactoryTest {

    @Test
    @DisplayName("전역 알림 SSE는 명시 timeout을 두지 않는다")
    void createNoTimeoutEmitter() {
        // 테스트 목적:
        // 업무상 종료 시간이 없는 전역 알림 SSE 연결이
        // Servlet async timeout 없음 의미의 0ms timeout으로 생성되는지 검증한다.

        // given
        NotificationSseEmitterFactory factory = new NotificationSseEmitterFactory();

        // when
        SseEmitter emitter = factory.create();

        // then
        assertThat(emitter.getTimeout()).isZero();
    }
}
