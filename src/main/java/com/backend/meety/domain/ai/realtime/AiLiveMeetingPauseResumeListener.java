package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.recording.event.RecordingPausedEvent;
import com.backend.meety.domain.recording.event.RecordingResumedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AiLiveMeetingPauseResumeListener {

    private final AiLiveMeetingConnectionService connectionService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void pauseAiLiveMeeting(RecordingPausedEvent event) {
        connectionService.pause(event.recordingSessionId());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void resumeAiLiveMeeting(RecordingResumedEvent event) {
        connectionService.resume(event.recordingSessionId());
    }
}
