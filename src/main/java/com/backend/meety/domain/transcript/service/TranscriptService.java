package com.backend.meety.domain.transcript.service;

import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentMessage;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptService {

    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingService meetingService;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void saveFinalSegment(AiTranscriptSegmentMessage message) {
        String sourceSegmentKey = message.sourceSegmentKey();
        if (transcriptSegmentRepository.existsBySourceSegmentKey(sourceSegmentKey)) {
            log.debug("중복 transcript segment 저장을 건너뜁니다. meetingId={}, sourceSegmentKey={}",
                    message.meetingId(), sourceSegmentKey);
            return;
        }

        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(message.meetingId())
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        TranscriptSegment segment = TranscriptSegment.createFinal(
                meeting,
                sourceSegmentKey,
                message.payload().sequenceNumber(),
                message.payload().content(),
                message.payload().startedAtMs(),
                message.payload().endedAtMs(),
                message.payload().recognizedAt()
        );

        TranscriptSegment savedSegment;
        try {
            savedSegment = transcriptSegmentRepository.saveAndFlush(segment);
        } catch (DataIntegrityViolationException e) {
            log.debug("중복 transcript segment 저장을 건너뜁니다. meetingId={}, sourceSegmentKey={}",
                    message.meetingId(), sourceSegmentKey);
            return;
        }

        log.debug("TranscriptSegment 저장 완료. transcriptSegmentId={}, meetingId={}, sequenceNumber={}, sourceSegmentKey={}",
                savedSegment.getId(), savedSegment.getMeeting().getId(), savedSegment.getSequenceNumber(),
                savedSegment.getSourceSegmentKey());
        eventPublisher.publishEvent(new TranscriptCreatedEvent(
                savedSegment.getMeeting().getId(),
                savedSegment.getId(),
                savedSegment.getSequenceNumber(),
                savedSegment.getContent(),
                savedSegment.getStartedAtMs(),
                savedSegment.getEndedAtMs(),
                savedSegment.getRecognizedAt()
        ));
        log.debug("TranscriptCreatedEvent 발행. transcriptSegmentId={}, meetingId={}",
                savedSegment.getId(), savedSegment.getMeeting().getId());
    }

    @Transactional(readOnly = true)
    public List<TranscriptSegmentResponse> getTranscripts(Long userId, Long meetingId) {
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        meetingService.validateMeetingAccess(userId, meeting.getTeam().getId());

        return transcriptSegmentRepository.findAllByMeetingIdOrderBySequence(meetingId).stream()
                .map(TranscriptSegmentResponse::from)
                .toList();
    }
}
