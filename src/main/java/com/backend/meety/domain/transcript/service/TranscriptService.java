package com.backend.meety.domain.transcript.service;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.exception.RecordingException;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

@Service
@RequiredArgsConstructor
public class TranscriptService {

    private final RecordingSessionRepository recordingSessionRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void saveCommitted(Long recordingSessionId, JsonNode event) {
        RecordingSession recordingSession = recordingSessionRepository.findByIdAndDeletedAtIsNull(recordingSessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        Meeting meeting = recordingSession.getMeeting();
        JsonNode payload = event.get("payload");
        Long sequenceNumber = payload.get("sequenceNumber").asLong();
        if (transcriptSegmentRepository.existsByMeetingIdAndSequenceNumber(meeting.getId(), sequenceNumber)) {
            return;
        }

        String content = payload.get("content").asText();
        Long startedAtMs = payload.get("startedAtMs").asLong();
        Long endedAtMs = payload.hasNonNull("endedAtMs") ? payload.get("endedAtMs").asLong() : null;
        LocalDateTime recognizedAt = OffsetDateTime.parse(payload.get("recognizedAt").asText()).toLocalDateTime();

        TranscriptSegment segment = TranscriptSegment.committed(
                meeting,
                sequenceNumber,
                content,
                startedAtMs,
                endedAtMs,
                recognizedAt
        );
        try {
            TranscriptSegment saved = transcriptSegmentRepository.saveAndFlush(segment);
            eventPublisher.publishEvent(new TranscriptCreatedEvent(
                    meeting.getId(),
                    recordingSessionId,
                    saved.getId(),
                    saved.getSequenceNumber(),
                    saved.getContent(),
                    saved.getStartedAtMs(),
                    saved.getEndedAtMs(),
                    saved.getRecognizedAt()
            ));
        } catch (DataIntegrityViolationException ignored) {
            // 같은 meetingId/sequenceNumber가 이미 저장된 중복 transcript는 broadcast하지 않는다.
        }
    }
}
