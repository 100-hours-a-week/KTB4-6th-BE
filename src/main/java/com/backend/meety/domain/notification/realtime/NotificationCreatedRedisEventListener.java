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
public class NotificationCreatedRedisEventListener {

    private final NotificationRedisPublisher notificationRedisPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publishNotificationCreated(NotificationCreatedEvent event) {
        try {
            notificationRedisPublisher.publish(event);
        } catch (RuntimeException e) {
            log.warn("알림 생성 Redis 이벤트 발행에 실패했습니다. notificationId={}, recipientUserId={}",
                    event.notificationId(), event.recipientUserId(), e);
        }
    }
}
