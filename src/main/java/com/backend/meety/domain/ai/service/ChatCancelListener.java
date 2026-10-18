package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ChatCancelListener {

    private final ChatProcessingService chatProcessingService;

    @Order(0)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void cancelProcessingChats(MeetingCompletedEvent event) {
        chatProcessingService.cancelProcessing(event.meetingId());
    }
}
