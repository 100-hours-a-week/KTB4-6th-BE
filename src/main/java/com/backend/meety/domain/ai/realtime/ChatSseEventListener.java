package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.event.ChatCompletedEvent;
import com.backend.meety.domain.ai.event.ChatFailedEvent;
import com.backend.meety.domain.ai.event.ChatRequestedEvent;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import com.backend.meety.domain.meeting.realtime.MeetingSsePublisher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatSseEventListener {

    private final AiChatbotMessageRepository chatbotMessageRepository;
    private final MeetingSsePublisher ssePublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastRequested(ChatRequestedEvent event) {
        chatbotMessageRepository.findWithAskerById(event.messageId())
                .map(message -> ChatRequestedSseEvent.of(ChatSseEventName.CHAT_REQUESTED, message, event.creditBalance()))
                .ifPresent(payload -> broadcast(event.meetingId(), ChatSseEventName.CHAT_REQUESTED, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastCompleted(ChatCompletedEvent event) {
        chatbotMessageRepository.findById(event.messageId())
                .map(message -> ChatCompletedSseEvent.of(ChatSseEventName.CHAT_COMPLETED, message))
                .ifPresent(payload -> broadcast(event.meetingId(), ChatSseEventName.CHAT_COMPLETED, payload));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastFailed(ChatFailedEvent event) {
        broadcast(event.meetingId(), ChatSseEventName.CHAT_FAILED,
                ChatFailedSseEvent.of(ChatSseEventName.CHAT_FAILED, event));
    }

    private void broadcast(Long meetingId, String eventName, Object payload) {
        ssePublisher.broadcast(meetingId, eventName, payload);
        log.debug("Chat SSE 발행 완료. meetingId={}, event={}", meetingId, eventName);
    }
}
