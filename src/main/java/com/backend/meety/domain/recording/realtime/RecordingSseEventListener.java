package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.meeting.realtime.MeetingSseRegistry;
import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import com.backend.meety.domain.recording.event.RecordingPausedEvent;
import com.backend.meety.domain.recording.event.RecordingResumedEvent;
import com.backend.meety.domain.recording.event.RecordingStartedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingSseEventListener {

    private static final String RECORDING_STARTED = "RECORDING_STARTED";
    private static final String RECORDING_PAUSED = "RECORDING_PAUSED";
    private static final String RECORDING_RESUMED = "RECORDING_RESUMED";
    private static final String RECORDING_COMPLETED = "RECORDING_COMPLETED";

    private final MeetingSseRegistry registry;

    @Order(0)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastStarted(RecordingStartedEvent event) {
        broadcast(event.meetingId(), event.recordingSessionId(), RECORDING_STARTED);
    }

    @Order(0)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastPaused(RecordingPausedEvent event) {
        broadcast(event.meetingId(), event.recordingSessionId(), RECORDING_PAUSED);
    }

    @Order(0)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastResumed(RecordingResumedEvent event) {
        broadcast(event.meetingId(), event.recordingSessionId(), RECORDING_RESUMED);
    }

    @Order(0)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastCompleted(RecordingCompletedEvent event) {
        broadcast(event.meetingId(), event.recordingSessionId(), RECORDING_COMPLETED);
    }

    private void broadcast(Long meetingId, Long recordingSessionId, String eventName) {
        RecordingStatusSseEvent payload = new RecordingStatusSseEvent(eventName, meetingId, recordingSessionId);
        int emitterCount = registry.broadcast(meetingId, eventName, payload);
        log.debug("Recording SSE broadcast 완료. meetingId={}, recordingSessionId={}, event={}, emitterCount={}",
                meetingId, recordingSessionId, eventName, emitterCount);
    }
}
