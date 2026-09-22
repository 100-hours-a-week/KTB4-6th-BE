package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AiLiveMeetingStopListener {

    private final AiLiveMeetingConnectionService connectionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void stopAiLiveMeeting(RecordingCompletedEvent event) {
        connectionService.stop(event.recordingSessionId());
    }
}
