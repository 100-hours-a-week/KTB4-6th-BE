package com.backend.meety.domain.meeting.realtime;

import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.domain.meeting.event.MeetingParticipantLeftEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class MeetingSseCleanupEventListener {

    private final MeetingSseRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void closeParticipantSse(MeetingParticipantLeftEvent event) {
        registry.complete(event.meetingId(), event.userId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void closeMeetingSse(MeetingCompletedEvent event) {
        registry.completeAll(event.meetingId());
    }
}
