package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.recording.event.RecordingCompletedEvent;
import com.backend.meety.domain.recording.owner.RecordingCommand;
import com.backend.meety.domain.recording.owner.RecordingCommandDispatch;
import com.backend.meety.domain.recording.owner.RecordingOwnerService;
import com.backend.meety.domain.transcript.realtime.TranscriptSaveDispatcher;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class AiLiveMeetingStopListener {

    private final RecordingOwnerService ownerService;
    private final ApplicationEventPublisher eventPublisher;
    private final TranscriptSaveDispatcher transcriptSaveDispatcher;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void stopAiLiveMeeting(RecordingCompletedEvent event) {
        RecordingCommandDispatch dispatch = ownerService.dispatch(event.recordingSessionId(), RecordingCommand.STOP);
        if (dispatch == RecordingCommandDispatch.NO_OWNER) {
            transcriptSaveDispatcher.submitFinalize(event.recordingSessionId(), () ->
                    eventPublisher.publishEvent(new MeetingTranscriptFinalizedEvent(
                            event.meetingId(), event.recordingSessionId())));
        }
    }
}
