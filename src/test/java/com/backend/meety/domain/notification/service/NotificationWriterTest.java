package com.backend.meety.domain.notification.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

class NotificationWriterTest {

    @Test
    @DisplayName("알림 저장 Writer는 별도 트랜잭션에서 실행된다")
    void writerUsesRequiresNewTransaction() throws NoSuchMethodException {
        // 테스트 목적:
        // 알림 저장 실패가 원본 비즈니스 트랜잭션에 영향을 주지 않도록
        // 알림 저장 메서드가 REQUIRES_NEW 트랜잭션 경계를 가지는지 검증한다.

        // given
        Method method = NotificationWriter.class.getMethod("create", NotificationWriteCommand.class);

        // when
        Transactional transactional = method.getAnnotation(Transactional.class);

        // then
        assertThat(transactional).isNotNull();
        assertThat(transactional.propagation()).isEqualTo(Propagation.REQUIRES_NEW);
    }
}
