package com.backend.meety.domain.notification.realtime;

import com.backend.meety.domain.notification.event.NotificationCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationCreatedSseEventListener {

    private final NotificationSseService notificationSseService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendNotificationCreated(NotificationCreatedEvent event) {
        try {
            notificationSseService.sendNotificationCreated(event);
        } catch (RuntimeException e) {
            log.warn("알림 생성 SSE 이벤트 전송에 실패했습니다. notificationId={}, recipientUserId={}",
                    event.notificationId(), event.recipientUserId(), e);
        }
    }
}
