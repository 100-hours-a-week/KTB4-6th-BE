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
import static org.mockito.ArgumentMatchers.anyString;
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
import com.backend.meety.global.exception.BusinessException;
import com.backend.meety.global.exception.CommonErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("AI 최종 전사 세그먼트는 저장 후 생성 이벤트를 발행한다")
    void saveFinalSegmentStoresTranscriptAndPublishesEvent() {
        // 테스트 목적:
        // AI가 전달한 최종 전사 세그먼트가 저장되고
        // 저장된 전사 기준으로 TranscriptCreatedEvent가 발행되는지 검증한다.

        // given
        when(transcriptSegments.saveAndFlush(any(TranscriptSegment.class)))
                .thenAnswer(call -> withId(call.getArgument(0), 900L));

        // when
        service.saveFinalSegment(message());

        // then
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
    @DisplayName("이미 저장된 sourceSegmentKey는 저장과 이벤트 발행을 건너뛴다")
    void duplicateSourceSegmentKeySkipsSaveAndEvent() {
        // 테스트 목적:
        // 같은 sourceSegmentKey의 전사가 이미 존재하는 경우
        // 중복 저장과 이벤트 발행을 수행하지 않는지 검증한다.

        // given
        when(transcriptSegments.existsBySourceSegmentKey("700:31")).thenReturn(true);

        // when
        service.saveFinalSegment(message());

        // then
        verify(transcriptSegments, never()).saveAndFlush(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("전사 저장 중 유니크 제약 충돌이 발생하면 이벤트 발행을 건너뛴다")
    void uniqueConstraintViolationSkipsEvent() {
        // 테스트 목적:
        // 동시 저장 등으로 sourceSegmentKey 유니크 제약 충돌이 발생하면
        // 중복 전사로 보고 이벤트를 발행하지 않는지 검증한다.

        // given
        when(transcriptSegments.saveAndFlush(any(TranscriptSegment.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate"));

        // when
        service.saveFinalSegment(message());

        // then
        verify(transcriptSegments).saveAndFlush(any(TranscriptSegment.class));
        verifyNoInteractions(eventPublisher);
    }

    @Test
    @DisplayName("keyword가 없으면 전체 전사를 저장된 발화 순서대로 반환한다")
    void getTranscriptsReturnsSegmentsInRepositoryOrder() {
        // 테스트 목적:
        // 검색어 없이 전사를 조회하면 기존 전체 전사 조회를 사용하고
        // repository가 반환한 발화 순서를 유지하는지 검증한다.

        // given
        TranscriptSegment first = segment(901L, 1L, "first text");
        TranscriptSegment second = segment(902L, 2L, "second text");
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(first, second));

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response).extracting("segmentId").containsExactly(901L, 902L);
        assertThat(response).extracting("sequenceNumber").containsExactly(1L, 2L);
        assertThat(response).extracting("content").containsExactly("first text", "second text");
        verify(meetingService).validateMeetingAccess(1L, 2L);
    }

    @Test
    @DisplayName("keyword 부분 일치 검색 결과를 발화 순서대로 반환한다")
    void getTranscriptsSearchesByKeyword() {
        // 테스트 목적:
        // 검색어가 있으면 전사 content 부분 일치 검색을 수행하고
        // 검색 결과를 repository가 반환한 발화 순서대로 응답하는지 검증한다.

        // given
        TranscriptSegment first = segment(901L, 3L, "카카오 프로젝트 일정입니다.");
        TranscriptSegment second = segment(902L, 5L, "카카오 배포 계획입니다.");
        when(transcriptSegments.searchByMeetingIdAndContent(100L, "카카오"))
                .thenReturn(List.of(first, second));

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L, "카카오");

        // then
        assertThat(response).extracting("segmentId").containsExactly(901L, 902L);
        assertThat(response).extracting("sequenceNumber").containsExactly(3L, 5L);
        assertThat(response).extracting("content")
                .containsExactly("카카오 프로젝트 일정입니다.", "카카오 배포 계획입니다.");
        verify(meetingService).validateMeetingAccess(1L, 2L);
        verify(transcriptSegments, never()).findAllByMeetingIdOrderBySequence(any());
    }

    @Test
    @DisplayName("검색 결과는 sequenceNumber ASC, id ASC 순서를 유지한다")
    void getTranscriptsSearchKeepsRepositoryOrder() {
        // 테스트 목적:
        // 검색 결과가 여러 건인 경우
        // DB 정렬 결과 순서를 Service가 변경하지 않고 반환하는지 검증한다.

        // given
        TranscriptSegment first = segment(902L, 1L, "검색 결과 A");
        TranscriptSegment second = segment(901L, 2L, "검색 결과 B");
        when(transcriptSegments.searchByMeetingIdAndContent(100L, "검색"))
                .thenReturn(List.of(first, second));

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L, "검색");

        // then
        assertThat(response).extracting("segmentId").containsExactly(902L, 901L);
        assertThat(response).extracting("sequenceNumber").containsExactly(1L, 2L);
    }

    @Test
    @DisplayName("영문 keyword는 대소문자 변환 없이 검색 쿼리로 전달한다")
    void getTranscriptsSearchPassesEnglishKeyword() {
        // 테스트 목적:
        // 영문 대소문자 무시 검색은 repository query의 lower 처리에 맡기고
        // Service가 검색어를 누락하지 않고 전달하는지 검증한다.

        // given
        when(transcriptSegments.searchByMeetingIdAndContent(100L, "Project")).thenReturn(List.of());

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L, "Project");

        // then
        assertThat(response).isEmpty();
        verify(transcriptSegments).searchByMeetingIdAndContent(100L, "Project");
    }

    @Test
    @DisplayName("keyword 앞뒤 공백은 제거한 뒤 검색한다")
    void getTranscriptsTrimsKeywordBeforeSearch() {
        // 테스트 목적:
        // 검색어 앞뒤에 공백이 포함된 경우
        // trim된 검색어로 부분 일치 검색을 수행하는지 검증한다.

        // given
        TranscriptSegment segment = segment(901L, 1L, "카카오 일정");
        when(transcriptSegments.searchByMeetingIdAndContent(100L, "카카오")).thenReturn(List.of(segment));

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L, "  카카오  ");

        // then
        assertThat(response).hasSize(1);
        verify(transcriptSegments).searchByMeetingIdAndContent(100L, "카카오");
    }

    @Test
    @DisplayName("검색 결과가 없으면 빈 목록을 반환한다")
    void getTranscriptsSearchReturnsEmptyList() {
        // 테스트 목적:
        // 검색어와 일치하는 전사가 없는 경우
        // 오류가 아닌 빈 목록을 반환하는지 검증한다.

        // given
        when(transcriptSegments.searchByMeetingIdAndContent(100L, "없음")).thenReturn(List.of());

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L, "없음");

        // then
        assertThat(response).isEmpty();
    }

    @Test
    @DisplayName("keyword가 정확히 2자이면 검색할 수 있다")
    void getTranscriptsAcceptsTwoCharacterKeyword() {
        // 테스트 목적:
        // 검색어 최소 길이인 2자 keyword가
        // 유효한 검색 조건으로 처리되는지 검증한다.

        // given
        when(transcriptSegments.searchByMeetingIdAndContent(100L, "회의")).thenReturn(List.of());

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L, "회의");

        // then
        assertThat(response).isEmpty();
        verify(transcriptSegments).searchByMeetingIdAndContent(100L, "회의");
    }

    @Test
    @DisplayName("keyword가 정확히 20자이면 검색할 수 있다")
    void getTranscriptsAcceptsTwentyCharacterKeyword() {
        // 테스트 목적:
        // 검색어 최대 길이인 20자 keyword가
        // 유효한 검색 조건으로 처리되는지 검증한다.

        // given
        String keyword = "가나다라마바사아자차카타파하가나다라마바";
        when(transcriptSegments.searchByMeetingIdAndContent(100L, keyword)).thenReturn(List.of());

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L, keyword);

        // then
        assertThat(response).isEmpty();
        verify(transcriptSegments).searchByMeetingIdAndContent(100L, keyword);
    }

    @Test
    @DisplayName("공백만 입력한 keyword는 검색어 없음으로 처리한다")
    void getTranscriptsTreatsBlankKeywordAsNoKeyword() {
        // 테스트 목적:
        // keyword가 공백만 포함하는 경우
        // 검색이 아닌 전체 전사 조회와 동일하게 처리되는지 검증한다.

        // given
        TranscriptSegment segment = segment(901L, 1L, "전체 전사");
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(segment));

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L, "   ");

        // then
        assertThat(response).extracting("content").containsExactly("전체 전사");
        verify(transcriptSegments).findAllByMeetingIdOrderBySequence(100L);
        verify(transcriptSegments, never()).searchByMeetingIdAndContent(any(), anyString());
    }

    @Test
    @DisplayName("전사가 없으면 빈 목록을 반환한다")
    void getTranscriptsReturnsEmptyListWhenNoTranscriptExists() {
        // 테스트 목적:
        // 회의는 존재하지만 저장된 전사가 없는 경우
        // 오류가 아닌 빈 목록을 반환하는지 검증한다.

        // given
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of());

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response).isEmpty();
        verify(meetingService).validateMeetingAccess(1L, 2L);
    }

    @Test
    @DisplayName("전사가 하나만 있어도 목록으로 반환한다")
    void getTranscriptsReturnsSingleSegment() {
        // 테스트 목적:
        // 조회된 전사가 단일 건인 경우에도
        // 동일한 목록 응답 구조로 반환되는지 검증한다.

        // given
        TranscriptSegment segment = segment(901L, 1L, "only text");
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(segment));

        // when
        List<TranscriptSegmentResponse> response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response).hasSize(1);
        assertThat(response.get(0).segmentId()).isEqualTo(901L);
        assertThat(response.get(0).sequenceNumber()).isEqualTo(1L);
        assertThat(response.get(0).content()).isEqualTo("only text");
    }

    @Test
    @DisplayName("존재하지 않거나 삭제된 회의는 전사를 조회할 수 없다")
    void getTranscriptsFailsWhenMeetingNotFoundOrDeleted() {
        // 테스트 목적:
        // 회의가 존재하지 않거나 삭제된 경우
        // 전사 조회가 MEETING_NOT_FOUND로 실패하는지 검증한다.

        // given
        when(meetings.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getTranscripts(1L, 404L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_NOT_FOUND);

        verifyNoInteractions(meetingService);
        verify(transcriptSegments, never()).findAllByMeetingIdOrderBySequence(any());
    }

    @Test
    @DisplayName("다른 팀 사용자는 전사를 조회할 수 없다")
    void getTranscriptsFailsWhenUserCannotAccessMeetingTeam() {
        // 테스트 목적:
        // 사용자가 회의가 속한 팀의 ACTIVE 멤버가 아닌 경우
        // 전사 조회가 MEETING_ACCESS_DENIED로 실패하는지 검증한다.

        // given
        org.mockito.Mockito.doThrow(new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED))
                .when(meetingService).validateMeetingAccess(99L, 2L);

        // when & then
        assertThatThrownBy(() -> service.getTranscripts(99L, 100L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_ACCESS_DENIED);

        verify(transcriptSegments, never()).findAllByMeetingIdOrderBySequence(any());
    }

    @Test
    @DisplayName("ACTIVE 멤버가 아닌 사용자는 전사를 검색할 수 없다")
    void searchTranscriptsFailsWhenUserIsNotActiveTeamMember() {
        // 테스트 목적:
        // 사용자가 회의 팀의 ACTIVE 멤버가 아닌 경우
        // 전사 검색도 기존 전사 조회와 동일하게 거부되는지 검증한다.

        // given
        org.mockito.Mockito.doThrow(new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED))
                .when(meetingService).validateMeetingAccess(1L, 2L);

        // when & then
        assertThatThrownBy(() -> service.getTranscripts(1L, 100L, "검색"))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_ACCESS_DENIED);

        verify(transcriptSegments, never()).searchByMeetingIdAndContent(any(), anyString());
    }

    @Test
    @DisplayName("keyword가 1자이면 전사 검색을 거부한다")
    void getTranscriptsRejectsOneCharacterKeyword() {
        // 테스트 목적:
        // 검색어가 최소 길이보다 짧은 경우
        // DB 조회 없이 입력값 오류로 거부되는지 검증한다.

        // given
        String keyword = "회";

        // when & then
        assertThatThrownBy(() -> service.getTranscripts(1L, 100L, keyword))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);

        verifyNoInteractions(meetingService);
        verify(transcriptSegments, never()).searchByMeetingIdAndContent(any(), anyString());
    }

    @Test
    @DisplayName("keyword가 21자 이상이면 전사 검색을 거부한다")
    void getTranscriptsRejectsTwentyOneCharacterKeyword() {
        // 테스트 목적:
        // 검색어가 최대 길이를 초과한 경우
        // DB 조회 없이 입력값 오류로 거부되는지 검증한다.

        // given
        String keyword = "가나다라마바사아자차카타파하가나다라마바사";

        // when & then
        assertThatThrownBy(() -> service.getTranscripts(1L, 100L, keyword))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(CommonErrorCode.INVALID_INPUT_VALUE);

        verifyNoInteractions(meetingService);
        verify(transcriptSegments, never()).searchByMeetingIdAndContent(any(), anyString());
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
