package com.backend.meety.domain.transcript.event;

import com.backend.meety.domain.meeting.realtime.MeetingEventBroadcaster;
import com.backend.meety.domain.meeting.realtime.MeetingRealtimeEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class TranscriptRealtimeEventListener {

    private final MeetingEventBroadcaster meetingEventBroadcaster;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void handle(TranscriptCreatedEvent event) {
        meetingEventBroadcaster.broadcast(MeetingRealtimeEvent.transcript(
                event.meetingId(),
                event.recordingSessionId(),
                event.transcriptSegmentId(),
                event.sequenceNumber(),
                event.content(),
                event.startedAtMs(),
                event.endedAtMs(),
                event.recognizedAt()
        ));
    }
}
