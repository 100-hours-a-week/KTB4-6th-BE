package com.backend.meety.domain.transcript.service;

import static com.backend.meety.domain.recording.RecordingFixtures.CLOCK;
import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentMessage;
import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentPayload;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

class TranscriptServiceTest {

    private final TranscriptSegmentRepository transcriptSegments = mock(TranscriptSegmentRepository.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TranscriptService service = new TranscriptService(transcriptSegments, meetings, CLOCK, eventPublisher);
    private Meeting meeting;

    @BeforeEach
    void setUp() {
        Team team = team();
        TeamMember member = member(team);
        meeting = meeting(team, member);
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
    }

    @Test
    void saveFinalSegmentStoresTranscriptAndPublishesEvent() {
        when(transcriptSegments.saveAndFlush(any(TranscriptSegment.class)))
                .thenAnswer(call -> withId(call.getArgument(0), 900L));

        service.saveFinalSegment(message("100:31"));

        ArgumentCaptor<TranscriptSegment> segmentCaptor = ArgumentCaptor.forClass(TranscriptSegment.class);
        verify(transcriptSegments).saveAndFlush(segmentCaptor.capture());
        TranscriptSegment segment = segmentCaptor.getValue();
        assertThat(segment.getMeeting()).isEqualTo(meeting);
        assertThat(segment.getSourceSegmentKey()).isEqualTo("100:31");
        assertThat(segment.getSequenceNumber()).isEqualTo(31L);
        assertThat(segment.getContent()).isEqualTo("final text");
        assertThat(segment.getStartedAtMs()).isEqualTo(12000L);
        assertThat(segment.getEndedAtMs()).isEqualTo(14500L);
        assertThat(segment.getRecognizedAt()).isEqualTo(NOW);
        assertThat(segment.getTranscriptSpeaker()).isNull();

        verify(eventPublisher).publishEvent(new TranscriptCreatedEvent(
                100L,
                900L,
                31L,
                "final text",
                12000L,
                14500L,
                NOW
        ));
    }

    @Test
    void duplicateSourceSegmentKeySkipsSaveAndEvent() {
        when(transcriptSegments.existsBySourceSegmentKey("100:31")).thenReturn(true);

        service.saveFinalSegment(message("100:31"));

        verify(transcriptSegments, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void uniqueConstraintViolationSkipsEvent() {
        when(transcriptSegments.saveAndFlush(any(TranscriptSegment.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        service.saveFinalSegment(message("100:31"));

        verify(transcriptSegments).saveAndFlush(any(TranscriptSegment.class));
        verifyNoInteractions(eventPublisher);
    }

    private AiTranscriptSegmentMessage message(String sourceSegmentKey) {
        return new AiTranscriptSegmentMessage(
                "transcript.segment.final",
                "evt_01J",
                100L,
                700L,
                new AiTranscriptSegmentPayload(
                        sourceSegmentKey,
                        31L,
                        12000L,
                        14500L,
                        "final text",
                        "gemini-3.5-transcribe",
                        "01"
                )
        );
    }
}
