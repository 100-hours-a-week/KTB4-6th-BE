package com.backend.meety.domain.notification.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.notification.entity.NotificationReferenceType;
import com.backend.meety.domain.notification.entity.NotificationType;
import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class NotificationCreatedSseEventListenerTest {

    @Test
    @DisplayName("알림 생성 이벤트를 SSE 서비스에 전달한다")
    void sendNotificationCreatedDelegatesToSseService() {
        // 테스트 목적:
        // Notification 저장 성공 후 발행된 이벤트가
        // 로컬 인스턴스의 알림 SSE 전송 서비스로 전달되는지 검증한다.

        // given
        NotificationSseService notificationSseService = mock(NotificationSseService.class);
        NotificationCreatedSseEventListener listener = new NotificationCreatedSseEventListener(notificationSseService);
        NotificationCreatedEvent event = notificationCreatedEvent();

        // when
        listener.sendNotificationCreated(event);

        // then
        verify(notificationSseService).sendNotificationCreated(event);
    }

    @Test
    @DisplayName("알림 생성 SSE listener는 커밋 이후 실행된다")
    void listenerRunsAfterCommit() throws NoSuchMethodException {
        // 테스트 목적:
        // NotificationWriter의 REQUIRES_NEW 저장 트랜잭션이 커밋된 뒤에만
        // 실시간 SSE 전송 listener가 실행되도록 트랜잭션 이벤트 phase를 검증한다.

        // given
        Method method = NotificationCreatedSseEventListener.class
                .getMethod("sendNotificationCreated", NotificationCreatedEvent.class);

        // when
        TransactionalEventListener annotation = method.getAnnotation(TransactionalEventListener.class);

        // then
        assertThat(annotation).isNotNull();
        assertThat(annotation.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    @DisplayName("SSE 전송 실패는 알림 생성 이벤트 처리를 실패시키지 않는다")
    void sseFailureDoesNotPropagate() {
        // 테스트 목적:
        // 알림은 이미 DB에 저장된 상태이므로 SSE 전송 계층에서 오류가 나도
        // 이벤트 listener 밖으로 예외를 전파하지 않는지 검증한다.

        // given
        NotificationSseService notificationSseService = mock(NotificationSseService.class);
        NotificationCreatedEvent event = notificationCreatedEvent();
        doThrow(new IllegalStateException("sse failed"))
                .when(notificationSseService).sendNotificationCreated(event);
        NotificationCreatedSseEventListener listener = new NotificationCreatedSseEventListener(notificationSseService);

        // when, then
        assertThatCode(() -> listener.sendNotificationCreated(event))
                .doesNotThrowAnyException();
    }

    private NotificationCreatedEvent notificationCreatedEvent() {
        return new NotificationCreatedEvent(
                15L,
                1L,
                NotificationType.MEMBER_JOINED,
                "test2 님이 팀에 합류했습니다",
                NotificationReferenceType.TEAM,
                null,
                false,
                LocalDateTime.of(2026, 10, 9, 13, 30, 12)
        );
    }
}
