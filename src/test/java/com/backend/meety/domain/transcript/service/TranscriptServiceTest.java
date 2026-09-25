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
import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentListResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingDetailResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingRequest;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingType;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerResponse;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import com.backend.meety.domain.transcript.exception.TranscriptErrorCode;
import com.backend.meety.domain.transcript.exception.TranscriptException;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSpeakerRepository;
import com.backend.meety.domain.user.entity.User;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.BeanUtils;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class TranscriptServiceTest {

    private final TranscriptSegmentRepository transcriptSegments = mock(TranscriptSegmentRepository.class);
    private final TranscriptSpeakerRepository transcriptSpeakers = mock(TranscriptSpeakerRepository.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final MeetingParticipantRepository meetingParticipants = mock(MeetingParticipantRepository.class);
    private final MeetingService meetingService = mock(MeetingService.class);
    private final TeamMemberRepository teamMembers = mock(TeamMemberRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final TranscriptService service = new TranscriptService(
            transcriptSegments,
            transcriptSpeakers,
            meetings,
            meetingParticipants,
            meetingService,
            teamMembers,
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response.segments()).extracting("segmentId").containsExactly(901L, 902L);
        assertThat(response.segments()).extracting("sequenceNumber").containsExactly(1L, 2L);
        assertThat(response.segments()).extracting("content").containsExactly("first text", "second text");
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L, "카카오");

        // then
        assertThat(response.segments()).extracting("segmentId").containsExactly(901L, 902L);
        assertThat(response.segments()).extracting("sequenceNumber").containsExactly(3L, 5L);
        assertThat(response.segments()).extracting("content")
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L, "검색");

        // then
        assertThat(response.segments()).extracting("segmentId").containsExactly(902L, 901L);
        assertThat(response.segments()).extracting("sequenceNumber").containsExactly(1L, 2L);
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L, "Project");

        // then
        assertThat(response.segments()).isEmpty();
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L, "  카카오  ");

        // then
        assertThat(response.segments()).hasSize(1);
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L, "없음");

        // then
        assertThat(response.segments()).isEmpty();
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L, "회의");

        // then
        assertThat(response.segments()).isEmpty();
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L, keyword);

        // then
        assertThat(response.segments()).isEmpty();
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L, "   ");

        // then
        assertThat(response.segments()).extracting("content").containsExactly("전체 전사");
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response.segments()).isEmpty();
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
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response.segments()).hasSize(1);
        assertThat(response.segments().get(0).segmentId()).isEqualTo(901L);
        assertThat(response.segments().get(0).sequenceNumber()).isEqualTo(1L);
        assertThat(response.segments().get(0).content()).isEqualTo("only text");
    }

    @Test
    @DisplayName("미매핑 발화자는 speakerLabel을 표시 이름으로 반환한다")
    void getTranscriptsReturnsSpeakerLabelWhenSpeakerIsUnmapped() {
        // 테스트 목적:
        // 발화자가 팀원이나 별칭에 매핑되지 않은 경우
        // 전사 조회 응답의 speakerDisplayName이 speakerLabel인지 검증한다.

        // given
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        TranscriptSegment segment = segment(901L, 1L, "미매핑 발화");
        setSpeaker(segment, speaker);
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(segment));

        // when
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response.segments()).extracting("speakerDisplayName").containsExactly("화자 1");
    }

    @Test
    @DisplayName("팀원 매핑 발화자는 팀원 nickname을 표시 이름으로 반환한다")
    void getTranscriptsReturnsMappedTeamMemberNickname() {
        // 테스트 목적:
        // 발화자가 팀원에 매핑된 경우
        // 전사 조회 응답의 speakerDisplayName이 팀원 nickname인지 검증한다.

        // given
        TeamMember mappedTeamMember = teamMember(10L, "김진혁");
        TranscriptSpeaker speaker = speaker(50L, "화자 1", mappedTeamMember, null);
        TranscriptSegment segment = segment(901L, 1L, "팀원 매핑 발화");
        setSpeaker(segment, speaker);
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(segment));

        // when
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response.segments()).extracting("speakerDisplayName").containsExactly("김진혁");
    }

    @Test
    @DisplayName("별칭 매핑 발화자는 customAlias를 표시 이름으로 반환한다")
    void getTranscriptsReturnsCustomAlias() {
        // 테스트 목적:
        // 발화자가 사용자 지정 별칭에 매핑된 경우
        // 전사 조회 응답의 speakerDisplayName이 customAlias인지 검증한다.

        // given
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, "게스트");
        TranscriptSegment segment = segment(901L, 1L, "별칭 매핑 발화");
        setSpeaker(segment, speaker);
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(segment));

        // when
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response.segments()).extracting("speakerDisplayName").containsExactly("게스트");
    }

    @Test
    @DisplayName("검색 결과에도 발화자 표시 이름을 적용한다")
    void searchTranscriptsAppliesSpeakerDisplayName() {
        // 테스트 목적:
        // 검색 결과로 반환된 전사에도 전체 조회와 동일하게
        // speakerDisplayName 계산 규칙이 적용되는지 검증한다.

        // given
        TeamMember mappedTeamMember = teamMember(10L, "김진혁");
        TranscriptSpeaker speaker = speaker(50L, "화자 1", mappedTeamMember, null);
        TranscriptSegment segment = segment(901L, 1L, "검색 대상 발화");
        setSpeaker(segment, speaker);
        when(transcriptSegments.searchByMeetingIdAndContent(100L, "검색")).thenReturn(List.of(segment));

        // when
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L, "검색");

        // then
        assertThat(response.segments()).extracting("speakerDisplayName").containsExactly("김진혁");
    }

    @Test
    @DisplayName("같은 발화자를 참조하는 여러 전사는 같은 표시 이름을 반환한다")
    void getTranscriptsReturnsSameDisplayNameForSameSpeaker() {
        // 테스트 목적:
        // 여러 전사 세그먼트가 같은 TranscriptSpeaker를 참조하는 경우
        // 동일한 speakerDisplayName이 반환되는지 검증한다.

        // given
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, "PM");
        TranscriptSegment first = segment(901L, 1L, "첫 번째 발화");
        TranscriptSegment second = segment(902L, 2L, "두 번째 발화");
        setSpeaker(first, speaker);
        setSpeaker(second, speaker);
        when(transcriptSegments.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(first, second));

        // when
        TranscriptSegmentListResponse response = service.getTranscripts(1L, 100L);

        // then
        assertThat(response.segments()).extracting("speakerDisplayName").containsExactly("PM", "PM");
    }

    @Test
    @DisplayName("발화자를 팀원과 연결한다")
    void updateSpeakerMappingMapsSpeakerToTeamMember() {
        // 테스트 목적:
        // teamMemberId만 입력한 경우 발화자의 기존 별칭을 제거하고
        // 회의에 참석했던 팀원으로 연결되는지 검증한다.

        // given
        TeamMember teamMember = member(meeting.getTeam());
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, "외부");
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));
        when(teamMembers.findById(10L)).thenReturn(Optional.of(teamMember));
        when(meetingParticipants.findByMeetingIdAndTeamMemberId(100L, 10L))
                .thenReturn(Optional.of(MeetingParticipant.create(meeting, teamMember)));

        // when
        TranscriptSpeakerResponse response = service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        );

        // then
        assertThat(response.transcriptSpeakerId()).isEqualTo(50L);
        assertThat(response.mappedTeamMemberId()).isEqualTo(10L);
        assertThat(response.customAlias()).isNull();
        assertThat(speaker.getMappedTeamMember()).isEqualTo(teamMember);
        assertThat(speaker.getCustomAlias()).isNull();
        verify(meetingService).validateMeetingAccess(1L, 2L);
    }

    @Test
    @DisplayName("현재 탈퇴 상태인 회의 참석자도 발화자에 매핑할 수 있다")
    void updateSpeakerMappingMapsLeftMeetingParticipantToSpeaker() {
        // 테스트 목적:
        // 과거 회의에 참석했던 팀원이 이후 탈퇴 상태가 된 경우에도
        // 참석 이력을 기준으로 발화자 매핑이 허용되는지 검증한다.

        // given
        TeamMember leftTeamMember = member(meeting.getTeam());
        leftTeamMember.leave(NOW);
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));
        when(teamMembers.findById(10L)).thenReturn(Optional.of(leftTeamMember));
        when(meetingParticipants.findByMeetingIdAndTeamMemberId(100L, 10L))
                .thenReturn(Optional.of(MeetingParticipant.create(meeting, leftTeamMember)));

        // when
        TranscriptSpeakerResponse response = service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        );

        // then
        assertThat(response.mappedTeamMemberId()).isEqualTo(10L);
        assertThat(response.customAlias()).isNull();
        assertThat(speaker.getMappedTeamMember()).isEqualTo(leftTeamMember);
    }

    @Test
    @DisplayName("현재 강퇴 상태인 회의 참석자도 발화자에 매핑할 수 있다")
    void updateSpeakerMappingMapsKickedMeetingParticipantToSpeaker() {
        // 테스트 목적:
        // 과거 회의에 참석했던 팀원이 이후 강퇴 상태가 된 경우에도
        // 참석 이력을 기준으로 발화자 매핑이 허용되는지 검증한다.

        // given
        TeamMember kickedTeamMember = member(meeting.getTeam());
        kickedTeamMember.kick(NOW);
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));
        when(teamMembers.findById(10L)).thenReturn(Optional.of(kickedTeamMember));
        when(meetingParticipants.findByMeetingIdAndTeamMemberId(100L, 10L))
                .thenReturn(Optional.of(MeetingParticipant.create(meeting, kickedTeamMember)));

        // when
        TranscriptSpeakerResponse response = service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        );

        // then
        assertThat(response.mappedTeamMemberId()).isEqualTo(10L);
        assertThat(response.customAlias()).isNull();
        assertThat(speaker.getMappedTeamMember()).isEqualTo(kickedTeamMember);
    }

    @Test
    @DisplayName("발화자를 customAlias와 연결한다")
    void updateSpeakerMappingMapsSpeakerToCustomAlias() {
        // 테스트 목적:
        // customAlias만 입력한 경우 발화자의 기존 팀원 연결을 제거하고
        // trim된 별칭으로 연결되는지 검증한다.

        // given
        TeamMember teamMember = member(meeting.getTeam());
        TranscriptSpeaker speaker = speaker(50L, "화자 1", teamMember, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));

        // when
        TranscriptSpeakerResponse response = service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(null, "  외부참석자  ")
        );

        // then
        assertThat(response.mappedTeamMemberId()).isNull();
        assertThat(response.customAlias()).isEqualTo("외부참석자");
        assertThat(speaker.getMappedTeamMember()).isNull();
        assertThat(speaker.getCustomAlias()).isEqualTo("외부참석자");
    }

    @Test
    @DisplayName("팀원 연결 상태에서 alias 연결로 변경한다")
    void updateSpeakerMappingChangesTeamMemberMappingToAlias() {
        // 테스트 목적:
        // 기존에 팀원과 연결된 발화자에 alias 매핑을 요청하면
        // 팀원 연결이 제거되고 별칭만 남는지 검증한다.

        // given
        TeamMember teamMember = member(meeting.getTeam());
        TranscriptSpeaker speaker = speaker(50L, "화자 1", teamMember, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));

        // when
        TranscriptSpeakerResponse response = service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(null, "게스트")
        );

        // then
        assertThat(response.mappedTeamMemberId()).isNull();
        assertThat(response.customAlias()).isEqualTo("게스트");
        assertThat(speaker.getMappedTeamMember()).isNull();
        assertThat(speaker.getCustomAlias()).isEqualTo("게스트");
    }

    @Test
    @DisplayName("alias 연결 상태에서 팀원 연결로 변경한다")
    void updateSpeakerMappingChangesAliasMappingToTeamMember() {
        // 테스트 목적:
        // 기존에 별칭과 연결된 발화자에 teamMemberId 매핑을 요청하면
        // 별칭이 제거되고 팀원 연결만 남는지 검증한다.

        // given
        TeamMember teamMember = member(meeting.getTeam());
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, "게스트");
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));
        when(teamMembers.findById(10L)).thenReturn(Optional.of(teamMember));
        when(meetingParticipants.findByMeetingIdAndTeamMemberId(100L, 10L))
                .thenReturn(Optional.of(MeetingParticipant.create(meeting, teamMember)));

        // when
        TranscriptSpeakerResponse response = service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        );

        // then
        assertThat(response.mappedTeamMemberId()).isEqualTo(10L);
        assertThat(response.customAlias()).isNull();
        assertThat(speaker.getMappedTeamMember()).isEqualTo(teamMember);
        assertThat(speaker.getCustomAlias()).isNull();
    }

    @Test
    @DisplayName("기존 연결을 해제한다")
    void updateSpeakerMappingClearsExistingMapping() {
        // 테스트 목적:
        // teamMemberId와 customAlias가 모두 null이면
        // 기존 발화자 매핑이 해제되는지 검증한다.

        // given
        TeamMember teamMember = member(meeting.getTeam());
        TranscriptSpeaker speaker = speaker(50L, "화자 1", teamMember, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));

        // when
        TranscriptSpeakerResponse response = service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(null, null)
        );

        // then
        assertThat(response.mappedTeamMemberId()).isNull();
        assertThat(response.customAlias()).isNull();
        assertThat(speaker.getMappedTeamMember()).isNull();
        assertThat(speaker.getCustomAlias()).isNull();
    }

    @Test
    @DisplayName("이미 미연결 상태에서 연결 해제 요청은 멱등적으로 처리한다")
    void updateSpeakerMappingClearIsIdempotentWhenAlreadyUnmapped() {
        // 테스트 목적:
        // 이미 팀원과 별칭이 모두 없는 발화자에 연결 해제를 요청해도
        // 오류 없이 동일한 미연결 상태를 유지하는지 검증한다.

        // given
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));

        // when
        TranscriptSpeakerResponse response = service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(null, null)
        );

        // then
        assertThat(response.mappedTeamMemberId()).isNull();
        assertThat(response.customAlias()).isNull();
        assertThat(speaker.getMappedTeamMember()).isNull();
        assertThat(speaker.getCustomAlias()).isNull();
    }

    @Test
    @DisplayName("teamMemberId와 customAlias가 모두 있으면 발화자 매핑을 거부한다")
    void updateSpeakerMappingRejectsTeamMemberAndAliasTogether() {
        // 테스트 목적:
        // 팀원 연결과 별칭 연결이 동시에 요청된 경우
        // 모호한 매핑 요청으로 보고 INVALID_SPEAKER_MAPPING으로 거부하는지 검증한다.

        // given
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, "외부참석자")
        ))
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.INVALID_SPEAKER_MAPPING);

        verifyNoInteractions(teamMembers, meetingParticipants);
    }

    @Test
    @DisplayName("공백만 있는 customAlias는 발화자 매핑을 거부한다")
    void updateSpeakerMappingRejectsBlankAlias() {
        // 테스트 목적:
        // customAlias가 공백만 포함하는 경우
        // 유효한 별칭으로 저장하지 않고 INVALID_SPEAKER_MAPPING으로 거부하는지 검증한다.

        // given
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(null, "   ")
        ))
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.INVALID_SPEAKER_MAPPING);
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
    @DisplayName("미매핑 발화자의 매핑 상세는 NONE 상태를 반환한다")
    void getSpeakerMappingReturnsNoneState() {
        // 테스트 목적:
        // segment가 참조하는 발화자에 팀원과 별칭 매핑이 모두 없는 경우
        // 매핑 상세 응답이 NONE 상태와 speakerLabel 표시 이름을 반환하는지 검증한다.

        // given
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        TranscriptSegment segment = segment(901L, 1L, "미매핑 발화");
        setSpeaker(segment, speaker);
        when(transcriptSegments.findByIdAndMeetingIdWithSpeaker(901L, 100L)).thenReturn(Optional.of(segment));
        when(meetingParticipants.findAllByMeetingIdWithTeamMember(100L)).thenReturn(List.of());

        // when
        TranscriptSpeakerMappingDetailResponse response = service.getSpeakerMapping(1L, 100L, 901L);

        // then
        assertThat(response.speaker().transcriptSpeakerId()).isEqualTo(50L);
        assertThat(response.speaker().speakerLabel()).isEqualTo("화자 1");
        assertThat(response.speaker().displayName()).isEqualTo("화자 1");
        assertThat(response.speaker().mappingType()).isEqualTo(TranscriptSpeakerMappingType.NONE);
        assertThat(response.speaker().mappedTeamMemberId()).isNull();
        assertThat(response.speaker().customAlias()).isNull();
        assertThat(response.participants()).isEmpty();
    }

    @Test
    @DisplayName("팀원 매핑 발화자의 매핑 상세는 TEAM_MEMBER 상태를 반환한다")
    void getSpeakerMappingReturnsTeamMemberState() {
        // 테스트 목적:
        // segment가 참조하는 발화자가 팀원에 매핑된 경우
        // 매핑 상세 응답이 TEAM_MEMBER 상태와 팀원 nickname을 반환하는지 검증한다.

        // given
        TeamMember mappedTeamMember = teamMember(10L, "김진혁");
        TranscriptSpeaker speaker = speaker(50L, "화자 1", mappedTeamMember, null);
        TranscriptSegment segment = segment(901L, 1L, "팀원 매핑 발화");
        setSpeaker(segment, speaker);
        when(transcriptSegments.findByIdAndMeetingIdWithSpeaker(901L, 100L)).thenReturn(Optional.of(segment));
        when(meetingParticipants.findAllByMeetingIdWithTeamMember(100L)).thenReturn(List.of());

        // when
        TranscriptSpeakerMappingDetailResponse response = service.getSpeakerMapping(1L, 100L, 901L);

        // then
        assertThat(response.speaker().displayName()).isEqualTo("김진혁");
        assertThat(response.speaker().mappingType()).isEqualTo(TranscriptSpeakerMappingType.TEAM_MEMBER);
        assertThat(response.speaker().mappedTeamMemberId()).isEqualTo(10L);
        assertThat(response.speaker().customAlias()).isNull();
    }

    @Test
    @DisplayName("별칭 매핑 발화자의 매핑 상세는 CUSTOM_ALIAS 상태를 반환한다")
    void getSpeakerMappingReturnsCustomAliasState() {
        // 테스트 목적:
        // segment가 참조하는 발화자가 사용자 지정 별칭에 매핑된 경우
        // 매핑 상세 응답이 CUSTOM_ALIAS 상태와 customAlias 표시 이름을 반환하는지 검증한다.

        // given
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, "외부");
        TranscriptSegment segment = segment(901L, 1L, "별칭 매핑 발화");
        setSpeaker(segment, speaker);
        when(transcriptSegments.findByIdAndMeetingIdWithSpeaker(901L, 100L)).thenReturn(Optional.of(segment));
        when(meetingParticipants.findAllByMeetingIdWithTeamMember(100L)).thenReturn(List.of());

        // when
        TranscriptSpeakerMappingDetailResponse response = service.getSpeakerMapping(1L, 100L, 901L);

        // then
        assertThat(response.speaker().displayName()).isEqualTo("외부");
        assertThat(response.speaker().mappingType()).isEqualTo(TranscriptSpeakerMappingType.CUSTOM_ALIAS);
        assertThat(response.speaker().mappedTeamMemberId()).isNull();
        assertThat(response.speaker().customAlias()).isEqualTo("외부");
    }

    @Test
    @DisplayName("발화자 매핑 상세는 해당 회의 참석자 목록을 반환한다")
    void getSpeakerMappingReturnsMeetingParticipants() {
        // 테스트 목적:
        // 발화자 매핑 상세 조회 시 같은 팀 전체 멤버가 아니라
        // 해당 회의의 MeetingParticipant 이력에 있는 팀원만 후보로 반환하는지 검증한다.

        // given
        TeamMember activeParticipant = teamMember(10L, "김진혁");
        TeamMember leftParticipant = teamMember(11L, "hoon");
        leftParticipant.leave(NOW);
        TeamMember kickedParticipant = teamMember(12L, "min");
        kickedParticipant.kick(NOW);
        TeamMember nonParticipant = teamMember(13L, "미참석");
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        TranscriptSegment segment = segment(901L, 1L, "후보 조회");
        setSpeaker(segment, speaker);
        when(transcriptSegments.findByIdAndMeetingIdWithSpeaker(901L, 100L)).thenReturn(Optional.of(segment));
        when(meetingParticipants.findAllByMeetingIdWithTeamMember(100L)).thenReturn(List.of(
                participant(101L, activeParticipant, ParticipationStatus.JOINED, null),
                participant(102L, leftParticipant, ParticipationStatus.JOINED, null),
                participant(103L, kickedParticipant, ParticipationStatus.JOINED, null)
        ));

        // when
        TranscriptSpeakerMappingDetailResponse response = service.getSpeakerMapping(1L, 100L, 901L);

        // then
        assertThat(response.participants()).extracting("teamMemberId").containsExactly(10L, 11L, 12L);
        assertThat(response.participants()).extracting("nickname").containsExactly("김진혁", "hoon", "min");
        assertThat(response.participants()).extracting("teamMemberId").doesNotContain(nonParticipant.getId());
    }

    @Test
    @DisplayName("다른 회의의 segment는 매핑 상세를 조회할 수 없다")
    void getSpeakerMappingFailsWhenSegmentBelongsToOtherMeeting() {
        // 테스트 목적:
        // segmentId가 존재하더라도 요청 meetingId에 속하지 않는 경우
        // TRANSCRIPT_SEGMENT_NOT_FOUND로 거부되는지 검증한다.

        // given
        when(transcriptSegments.findByIdAndMeetingIdWithSpeaker(901L, 100L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getSpeakerMapping(1L, 100L, 901L))
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.TRANSCRIPT_SEGMENT_NOT_FOUND);

        verifyNoInteractions(meetingParticipants);
    }

    @Test
    @DisplayName("존재하지 않는 segment는 매핑 상세를 조회할 수 없다")
    void getSpeakerMappingFailsWhenSegmentNotFound() {
        // 테스트 목적:
        // 요청한 meetingId와 segmentId에 해당하는 전사 세그먼트가 없는 경우
        // TRANSCRIPT_SEGMENT_NOT_FOUND로 거부되는지 검증한다.

        // given
        when(transcriptSegments.findByIdAndMeetingIdWithSpeaker(404L, 100L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getSpeakerMapping(1L, 100L, 404L))
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.TRANSCRIPT_SEGMENT_NOT_FOUND);

        verifyNoInteractions(meetingParticipants);
    }

    @Test
    @DisplayName("존재하지 않는 회의는 발화자 매핑 상세를 조회할 수 없다")
    void getSpeakerMappingFailsWhenMeetingNotFound() {
        // 테스트 목적:
        // 회의가 존재하지 않거나 삭제된 경우
        // 발화자 매핑 상세 조회가 MEETING_NOT_FOUND로 실패하는지 검증한다.

        // given
        when(meetings.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.getSpeakerMapping(1L, 404L, 901L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_NOT_FOUND);

        verifyNoInteractions(meetingService);
        verify(transcriptSegments, never()).findByIdAndMeetingIdWithSpeaker(any(), any());
    }

    @Test
    @DisplayName("접근 권한이 없는 사용자는 발화자 매핑 상세를 조회할 수 없다")
    void getSpeakerMappingFailsWhenUserCannotAccessMeetingTeam() {
        // 테스트 목적:
        // 사용자가 회의가 속한 팀의 ACTIVE 멤버가 아닌 경우
        // 발화자 매핑 상세 조회가 MEETING_ACCESS_DENIED로 실패하는지 검증한다.

        // given
        org.mockito.Mockito.doThrow(new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED))
                .when(meetingService).validateMeetingAccess(99L, 2L);

        // when & then
        assertThatThrownBy(() -> service.getSpeakerMapping(99L, 100L, 901L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_ACCESS_DENIED);

        verify(transcriptSegments, never()).findByIdAndMeetingIdWithSpeaker(any(), any());
    }

    @Test
    @DisplayName("발화자가 없는 segment는 매핑 상세를 조회할 수 없다")
    void getSpeakerMappingFailsWhenSegmentHasNoSpeaker() {
        // 테스트 목적:
        // segment는 존재하지만 연결된 TranscriptSpeaker가 없는 경우
        // 매핑할 발화자가 없으므로 TRANSCRIPT_SPEAKER_NOT_FOUND로 실패하는지 검증한다.

        // given
        TranscriptSegment segment = segment(901L, 1L, "발화자 없음");
        when(transcriptSegments.findByIdAndMeetingIdWithSpeaker(901L, 100L)).thenReturn(Optional.of(segment));

        // when & then
        assertThatThrownBy(() -> service.getSpeakerMapping(1L, 100L, 901L))
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.TRANSCRIPT_SPEAKER_NOT_FOUND);

        verifyNoInteractions(meetingParticipants);
    }

    @Test
    @DisplayName("존재하지 않는 회의는 발화자 매핑을 수정할 수 없다")
    void updateSpeakerMappingFailsWhenMeetingNotFound() {
        // 테스트 목적:
        // 회의가 존재하지 않는 경우
        // 발화자 매핑 수정이 MEETING_NOT_FOUND로 실패하는지 검증한다.

        // given
        when(meetings.findByIdAndDeletedAtIsNull(404L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                1L,
                404L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_NOT_FOUND);

        verifyNoInteractions(meetingService);
        verify(transcriptSpeakers, never()).findByIdAndMeetingIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    @DisplayName("삭제된 회의는 발화자 매핑을 수정할 수 없다")
    void updateSpeakerMappingFailsWhenMeetingDeleted() {
        // 테스트 목적:
        // 삭제된 회의가 repository의 deletedAt 조건으로 제외되는 경우
        // 발화자 매핑 수정이 MEETING_NOT_FOUND로 실패하는지 검증한다.

        // given
        when(meetings.findByIdAndDeletedAtIsNull(405L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                1L,
                405L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_NOT_FOUND);

        verifyNoInteractions(meetingService);
        verify(transcriptSpeakers, never()).findByIdAndMeetingIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    @DisplayName("접근 권한이 없는 사용자는 발화자 매핑을 수정할 수 없다")
    void updateSpeakerMappingFailsWhenUserCannotAccessMeetingTeam() {
        // 테스트 목적:
        // 사용자가 회의가 속한 팀의 ACTIVE 멤버가 아닌 경우
        // 발화자 매핑 수정이 MEETING_ACCESS_DENIED로 실패하는지 검증한다.

        // given
        org.mockito.Mockito.doThrow(new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED))
                .when(meetingService).validateMeetingAccess(99L, 2L);

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                99L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_ACCESS_DENIED);

        verify(transcriptSpeakers, never()).findByIdAndMeetingIdAndDeletedAtIsNull(any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 발화자는 매핑을 수정할 수 없다")
    void updateSpeakerMappingFailsWhenSpeakerNotFound() {
        // 테스트 목적:
        // transcriptSpeakerId에 해당하는 유효한 발화자가 없는 경우
        // 발화자 매핑 수정이 TRANSCRIPT_SPEAKER_NOT_FOUND로 실패하는지 검증한다.

        // given
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(404L, 100L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                1L,
                100L,
                404L,
                new TranscriptSpeakerMappingRequest(10L, null)
        ))
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.TRANSCRIPT_SPEAKER_NOT_FOUND);

        verifyNoInteractions(teamMembers, meetingParticipants);
    }

    @Test
    @DisplayName("다른 회의의 발화자는 매핑을 수정할 수 없다")
    void updateSpeakerMappingFailsWhenSpeakerBelongsToOtherMeeting() {
        // 테스트 목적:
        // transcriptSpeakerId가 존재하더라도 요청 meetingId의 발화자가 아닌 경우
        // 회의 범위 조회에서 제외되어 TRANSCRIPT_SPEAKER_NOT_FOUND로 실패하는지 검증한다.

        // given
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        ))
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.TRANSCRIPT_SPEAKER_NOT_FOUND);

        verifyNoInteractions(teamMembers, meetingParticipants);
    }

    @Test
    @DisplayName("존재하지 않는 팀원에는 발화자를 연결할 수 없다")
    void updateSpeakerMappingFailsWhenTeamMemberNotFound() {
        // 테스트 목적:
        // teamMemberId 자체가 존재하지 않는 경우
        // 발화자 매핑 수정이 TEAM_MEMBER_NOT_FOUND로 실패하는지 검증한다.

        // given
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));
        when(teamMembers.findById(404L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(404L, null)
        ))
                .isInstanceOf(TeamException.class)
                .extracting("errorCode")
                .isEqualTo(TeamErrorCode.TEAM_MEMBER_NOT_FOUND);

        verifyNoInteractions(meetingParticipants);
    }

    @Test
    @DisplayName("같은 팀이지만 해당 회의에 참석하지 않은 팀원에는 발화자를 연결할 수 없다")
    void updateSpeakerMappingFailsWhenTeamMemberDidNotParticipateInMeeting() {
        // 테스트 목적:
        // 존재하는 팀원이고 같은 팀 소속 이력이 있더라도 해당 회의 참석 이력이 없는 경우
        // 발화자 매핑 수정이 TEAM_MEMBER_NOT_MEETING_PARTICIPANT로 실패하는지 검증한다.

        // given
        TeamMember teamMember = member(meeting.getTeam());
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));
        when(teamMembers.findById(10L)).thenReturn(Optional.of(teamMember));
        when(meetingParticipants.findByMeetingIdAndTeamMemberId(100L, 10L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        ))
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.TEAM_MEMBER_NOT_MEETING_PARTICIPANT);
    }

    @Test
    @DisplayName("다른 팀이고 해당 회의에 참석하지 않은 팀원에는 발화자를 연결할 수 없다")
    void updateSpeakerMappingFailsWhenOtherTeamMemberDidNotParticipateInMeeting() {
        // 테스트 목적:
        // 존재하는 팀원이지만 요청 회의의 참석 이력이 없는 경우
        // 팀 소속과 무관하게 TEAM_MEMBER_NOT_MEETING_PARTICIPANT로 실패하는지 검증한다.

        // given
        Team otherTeam = withId(Team.create("다른팀"), 3L);
        TeamMember otherTeamMember = member(otherTeam);
        TranscriptSpeaker speaker = speaker(50L, "화자 1", null, null);
        when(transcriptSpeakers.findByIdAndMeetingIdAndDeletedAtIsNull(50L, 100L)).thenReturn(Optional.of(speaker));
        when(teamMembers.findById(10L)).thenReturn(Optional.of(otherTeamMember));
        when(meetingParticipants.findByMeetingIdAndTeamMemberId(100L, 10L)).thenReturn(Optional.empty());

        // when & then
        assertThatThrownBy(() -> service.updateSpeakerMapping(
                1L,
                100L,
                50L,
                new TranscriptSpeakerMappingRequest(10L, null)
        ))
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.TEAM_MEMBER_NOT_MEETING_PARTICIPANT);
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
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.INVALID_TRANSCRIPT_KEYWORD);

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
                .isInstanceOf(TranscriptException.class)
                .extracting("errorCode")
                .isEqualTo(TranscriptErrorCode.INVALID_TRANSCRIPT_KEYWORD);

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

    private void setSpeaker(TranscriptSegment segment, TranscriptSpeaker speaker) {
        ReflectionTestUtils.setField(segment, "transcriptSpeaker", speaker);
    }

    private TranscriptSpeaker speaker(Long id, String speakerLabel, TeamMember mappedTeamMember, String customAlias) {
        TranscriptSpeaker speaker = BeanUtils.instantiateClass(TranscriptSpeaker.class);
        ReflectionTestUtils.setField(speaker, "id", id);
        ReflectionTestUtils.setField(speaker, "meeting", meeting);
        ReflectionTestUtils.setField(speaker, "mappedTeamMember", mappedTeamMember);
        ReflectionTestUtils.setField(speaker, "speakerLabel", speakerLabel);
        ReflectionTestUtils.setField(speaker, "customAlias", customAlias);
        return speaker;
    }

    private TeamMember teamMember(Long id, String displayName) {
        return withId(TeamMember.createMember(withId(User.create(), id), meeting.getTeam(), displayName), id);
    }

    private MeetingParticipant participant(
            Long id,
            TeamMember teamMember,
            ParticipationStatus participationStatus,
            LocalDateTime deletedAt
    ) {
        MeetingParticipant participant = MeetingParticipant.create(meeting, teamMember);
        ReflectionTestUtils.setField(participant, "id", id);
        ReflectionTestUtils.setField(participant, "participationStatus", participationStatus);
        ReflectionTestUtils.setField(participant, "deletedAt", deletedAt);
        return participant;
    }
}
