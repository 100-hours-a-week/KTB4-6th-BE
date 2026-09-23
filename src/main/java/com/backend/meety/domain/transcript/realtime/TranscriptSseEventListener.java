package com.backend.meety.domain.transcript.realtime;

import com.backend.meety.domain.meeting.realtime.MeetingSseRegistry;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Slf4j
@Component
@RequiredArgsConstructor
public class TranscriptSseEventListener {

    private static final String TRANSCRIPT_CREATED_EVENT_NAME = "TRANSCRIPT_CREATED";

    private final MeetingSseRegistry registry;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void broadcastTranscriptCreated(TranscriptCreatedEvent event) {
        TranscriptCreatedSseEvent payload = TranscriptCreatedSseEvent.of(
                event.meetingId(),
                event.transcriptSegmentId(),
                event.sequenceNumber(),
                event.content(),
                event.startedAtMs(),
                event.endedAtMs(),
                event.recognizedAt()
        );
        int emitterCount = registry.broadcast(event.meetingId(), TRANSCRIPT_CREATED_EVENT_NAME, payload);
        log.debug("TRANSCRIPT_CREATED broadcast 완료. meetingId={}, transcriptSegmentId={}, sequenceNumber={}, emitterCount={}",
                event.meetingId(), event.transcriptSegmentId(), event.sequenceNumber(), emitterCount);
    }
}
