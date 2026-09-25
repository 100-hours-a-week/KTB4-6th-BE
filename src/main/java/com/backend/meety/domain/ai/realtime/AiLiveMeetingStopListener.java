package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import org.springframework.context.ApplicationEventPublisher;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AiLiveMeetingStopListener {

    private final AiLiveMeetingConnectionService connectionService;
    private final AiLiveMeetingConnectionRegistry registry;
    private final ApplicationEventPublisher eventPublisher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void stopAiLiveMeeting(RecordingCompletedEvent event) {
        boolean connected = registry.find(event.recordingSessionId()).isPresent();
        connectionService.stop(event.recordingSessionId());
        if (!connected) {
            eventPublisher.publishEvent(new MeetingTranscriptFinalizedEvent(
                    event.meetingId(), event.recordingSessionId()));
        }
    }
}
