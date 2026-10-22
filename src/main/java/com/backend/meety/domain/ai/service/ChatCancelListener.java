package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.global.config.AsyncConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class ChatCancelListener {

    private final ChatProcessingService chatProcessingService;

    @Order(0)
    @Async(AsyncConfig.CHAT_TASK_EXECUTOR_BEAN_NAME)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void cancelProcessingChats(MeetingCompletedEvent event) {
        chatProcessingService.cancelProcessing(event.meetingId());
    }
}
