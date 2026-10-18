package com.backend.meety.domain.meeting.realtime;

import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.domain.meeting.event.MeetingDeletedEvent;
import com.backend.meety.domain.meeting.event.MeetingParticipantLeftEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class MeetingSseCleanupEventListener {

    private static final String MEETING_DELETED_EVENT_NAME = "MEETING_DELETED";

    private final MeetingSsePublisher ssePublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void closeParticipantSse(MeetingParticipantLeftEvent event) {
        ssePublisher.completeParticipant(event.meetingId(), event.userId());
    }

    @Order(Ordered.LOWEST_PRECEDENCE)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void closeMeetingSse(MeetingCompletedEvent event) {
        ssePublisher.completeAll(event.meetingId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sendDeletedEventAndCloseMeetingSse(MeetingDeletedEvent event) {
        ssePublisher.sendAndCompleteAll(
                event.meetingId(),
                MEETING_DELETED_EVENT_NAME,
                MeetingSseDeletedEvent.deleted(event.meetingId())
        );
    }
}
