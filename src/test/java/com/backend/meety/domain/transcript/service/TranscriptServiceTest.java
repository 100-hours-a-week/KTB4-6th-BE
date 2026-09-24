package com.backend.meety.domain.transcript.service;

import static com.backend.meety.domain.recording.RecordingFixtures.CLOCK;
import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentMessage;
import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentPayload;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

class TranscriptServiceTest {

    private final TranscriptSegmentRepository transcriptSegments = mock(TranscriptSegmentRepository.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final MeetingService meetingService = mock(MeetingService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TranscriptService service = new TranscriptService(
            transcriptSegments,
            meetings,
            meetingService,
            CLOCK,
            eventPublisher
    );
    private static final LocalDateTime RECOGNIZED_AT = LocalDateTime.of(2026, 9, 21, 5, 30, 4, 500_000_000);

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

        service.saveFinalSegment(message());

        ArgumentCaptor<TranscriptSegment> segmentCaptor = ArgumentCaptor.forClass(TranscriptSegment.class);
        verify(transcriptSegments).saveAndFlush(segmentCaptor.capture());
        TranscriptSegment segment = segmentCaptor.getValue();
        assertThat(segment.getMeeting()).isEqualTo(meeting);
        assertThat(segment.getSourceSegmentKey()).isEqualTo("700:31");
        assertThat(segment.getSequenceNumber()).isEqualTo(31L);
        assertThat(segment.getContent()).isEqualTo("final text");
        assertThat(segment.getStartedAtMs()).isEqualTo(12000L);
        assertThat(segment.getEndedAtMs()).isEqualTo(14500L);
        assertThat(segment.getRecognizedAt()).isEqualTo(RECOGNIZED_AT);
        assertThat(segment.getTranscriptSpeaker()).isNull();

        verify(eventPublisher).publishEvent(new TranscriptCreatedEvent(
                100L,
                900L,
                31L,
                "final text",
                12000L,
                14500L,
                RECOGNIZED_AT
        ));
    }

    @Test
    void duplicateSourceSegmentKeySkipsSaveAndEvent() {
        when(transcriptSegments.existsBySourceSegmentKey("700:31")).thenReturn(true);

        service.saveFinalSegment(message());

        verify(transcriptSegments, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void uniqueConstraintViolationSkipsEvent() {
        when(transcriptSegments.saveAndFlush(any(TranscriptSegment.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        service.saveFinalSegment(message());

        verify(transcriptSegments).saveAndFlush(any(TranscriptSegment.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void getTranscriptsReturnsSegmentsInRepositoryOrder() {
        TranscriptSegment first = segment(901L, 1L, "first text");
        TranscriptSegment second = segment(902L, 2L, "second text");
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(first, second));

        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L);

        assertThat(response).extracting("segmentId").containsExactly(901L, 902L);
        assertThat(response).extracting("sequenceNumber").containsExactly(1L, 2L);
        assertThat(response).extracting("content").containsExactly("first text", "second text");
        verify(meetingService).validateMeetingAccess(1L, 2L);
    }

    @Test
    void getTranscriptsReturnsEmptyListWhenNoTranscriptExists() {
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of());

        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L);

        assertThat(response).isEmpty();
        verify(meetingService).validateMeetingAccess(1L, 2L);
    }

    @Test
    void getTranscriptsReturnsSingleSegment() {
        TranscriptSegment segment = segment(901L, 1L, "only text");
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(segment));

        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L);

        assertThat(response).hasSize(1);
        assertThat(response.get(0).segmentId()).isEqualTo(901L);
        assertThat(response.get(0).sequenceNumber()).isEqualTo(1L);
        assertThat(response.get(0).content()).isEqualTo("only text");
    }

    @Test
    void getTranscriptsFailsWhenMeetingNotFoundOrDeleted() {
        when(meetings.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getTranscripts(1L, 404L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_NOT_FOUND);

        verifyNoInteractions(meetingService);
        verify(transcriptSegments, never()).findAllByMeetingIdOrderBySequence(any());
    }

    @Test
    void getTranscriptsFailsWhenUserCannotAccessMeetingTeam() {
        org.mockito.Mockito.doThrow(new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED))
                .when(meetingService).validateMeetingAccess(99L, 2L);

        assertThatThrownBy(() -> service.getTranscripts(99L, 100L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_ACCESS_DENIED);

        verify(transcriptSegments, never()).findAllByMeetingIdOrderBySequence(any());
    }

    private AiTranscriptSegmentMessage message() {
        return new AiTranscriptSegmentMessage(
                AiTranscriptSegmentMessage.TYPE,
                100L,
                700L,
                new AiTranscriptSegmentPayload(31L, "final text", 12000L, 14500L, RECOGNIZED_AT)
        );
    }

    private TranscriptSegment segment(Long id, Long sequenceNumber, String content) {
        return withId(TranscriptSegment.createFinal(
                meeting,
                "700:" + sequenceNumber,
                sequenceNumber,
                content,
                sequenceNumber * 1000,
                sequenceNumber * 1000 + 500,
                RECOGNIZED_AT
        ), id);
    }
}
