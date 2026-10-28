package com.backend.meety.domain.ai.service;

import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.session;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.dto.InternalSummaryResponse;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.exception.AiChatErrorCode;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.meeting.dto.MeetingListResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.meeting.service.MeetingSummaryService;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentListResponse;
import com.backend.meety.domain.transcript.service.TranscriptService;
import com.backend.meety.global.exception.BaseCode;
import com.backend.meety.global.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AiInternalQueryServiceTest {

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final MeetingService meetingService = mock(MeetingService.class);
    private final TranscriptService transcriptService = mock(TranscriptService.class);
    private final MeetingSummaryService summaryService = mock(MeetingSummaryService.class);
    private final RecordingSessionRepository recordingSessions = mock(RecordingSessionRepository.class);
    private final AiInternalQueryService service = new AiInternalQueryService(
            aiRequests, meetings, meetingService, transcriptService, summaryService, recordingSessions);

    private Team team;
    private TeamMember member;
    private AiRequest chatRequest;
    private RecordingSession recording;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
        chatRequest = withId(AiRequest.create(team, member, "key", AiRequestType.CHAT), 900L);
        chatRequest.markProcessing();
        when(aiRequests.findById(900L)).thenReturn(Optional.of(chatRequest));
        Meeting meeting = meeting(team, member);
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        recording = session(meeting, member);
        when(recordingSessions.findByIdWithMeetingTeamAndStarter(700L)).thenReturn(Optional.of(recording));
    }

    @Test
    @DisplayName("회의 목록은 경로의 teamId가 아니라 aiRequestId의 팀으로 조회한다")
    void listsMeetingsOfRequestTeam() {
        MeetingListResponse expected = new MeetingListResponse(List.of(), null, false);
        when(meetingService.findMeetings(2L, "배포", null, null, null)).thenReturn(expected);

        assertThat(service.getMeetings(900L, null, 3L, "배포", null, null, null)).isSameAs(expected);
    }

    @Test
    @DisplayName("녹음 세션으로 조회할 때 경로의 teamId가 녹음 세션의 팀과 다르면 회의 목록을 거절한다")
    void rejectsMeetingsOfOtherTeamPathWithRecordingSession() {
        assertCode(() -> service.getMeetings(null, 700L, 3L, null, null, null, null), TeamErrorCode.TEAM_ACCESS_DENIED);
    }

    @Test
    @DisplayName("같은 팀 회의의 전사를 조회한다")
    void getsTranscriptsOfTeamMeeting() {
        TranscriptSegmentListResponse expected = new TranscriptSegmentListResponse(List.of());
        when(transcriptService.findTranscripts(100L, null)).thenReturn(expected);

        assertThat(service.getTranscripts(900L, null, 100L, null)).isSameAs(expected);
    }

    @Test
    @DisplayName("완료된 요약이 없으면 오류가 아니라 summary가 비어 있는 응답을 준다")
    void returnsEmptySummaryWhenNone() {
        when(summaryService.findLatestCompletedSummary(100L)).thenReturn(Optional.empty());

        InternalSummaryResponse response = service.getLatestSummary(900L, null, 100L);

        assertThat(response.summary()).isNull();
        verify(summaryService).findLatestCompletedSummary(100L);
    }

    @Test
    @DisplayName("처리 중인 CHAT 질문이 아니면 거절한다")
    void rejectsRequestNotProcessingChat() {
        chatRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
        assertCode(() -> service.getMeetings(900L, null, 2L, null, null, null, null), AiChatErrorCode.AI_REQUEST_ACCESS_DENIED);

        AiRequest summaryRequest = withId(AiRequest.create(team, member, "s", AiRequestType.SUMMARY), 901L);
        when(aiRequests.findById(901L)).thenReturn(Optional.of(summaryRequest));
        assertCode(() -> service.getMeetings(901L, null, 2L, null, null, null, null), AiChatErrorCode.AI_REQUEST_ACCESS_DENIED);

        assertCode(() -> service.getMeetings(999L, null, 2L, null, null, null, null), AiChatErrorCode.AI_REQUEST_ACCESS_DENIED);
    }

    @Test
    @DisplayName("다른 팀 회의는 조회할 수 없다")
    void rejectsOtherTeamMeeting() {
        Team otherTeam = withId(Team.create("다른 팀"), 3L);
        Meeting otherMeeting = withId(Meeting.create(otherTeam, member(otherTeam), "남의 회의", "테스트", null, NOW, 30), 300L);
        when(meetings.findByIdAndDeletedAtIsNull(300L)).thenReturn(Optional.of(otherMeeting));

        assertCode(() -> service.getTranscripts(900L, null, 300L, null), MeetingErrorCode.MEETING_ACCESS_DENIED);
        assertCode(() -> service.getLatestSummary(900L, null, 300L), MeetingErrorCode.MEETING_ACCESS_DENIED);
    }

    @Test
    @DisplayName("회의가 없으면 404다")
    void rejectsMissingMeeting() {
        assertCode(() -> service.getTranscripts(900L, null, 999L, null), MeetingErrorCode.MEETING_NOT_FOUND);
    }

    @Test
    @DisplayName("녹음 중이거나 일시정지된 녹음 세션이면 같은 팀의 회의 목록·전사·요약을 조회한다")
    void queriesWithActiveRecordingSession() {
        MeetingListResponse meetingsOfTeam = new MeetingListResponse(List.of(), null, false);
        TranscriptSegmentListResponse transcripts = new TranscriptSegmentListResponse(List.of());
        when(meetingService.findMeetings(2L, null, null, null, null)).thenReturn(meetingsOfTeam);
        when(transcriptService.findTranscripts(100L, null)).thenReturn(transcripts);
        when(summaryService.findLatestCompletedSummary(100L)).thenReturn(Optional.empty());

        assertThat(service.getMeetings(null, 700L, 2L, null, null, null, null)).isSameAs(meetingsOfTeam);
        assertThat(service.getTranscripts(null, 700L, 100L, null)).isSameAs(transcripts);
        assertThat(service.getLatestSummary(null, 700L, 100L).summary()).isNull();

        recording.pause(NOW);
        assertThat(service.getTranscripts(null, 700L, 100L, null)).isSameAs(transcripts);
    }

    @Test
    @DisplayName("준비 중·종료·실패한 녹음 세션이나 없는 세션으로는 조회할 수 없다")
    void rejectsInactiveRecordingSession() {
        for (RecordingSessionStatus status : List.of(
                RecordingSessionStatus.PREPARING, RecordingSessionStatus.COMPLETED, RecordingSessionStatus.FAILED)) {
            ReflectionTestUtils.setField(recording, "status", status);
            assertCode(() -> service.getTranscripts(null, 700L, 100L, null),
                    AiChatErrorCode.RECORDING_SESSION_ACCESS_DENIED);
        }

        assertCode(() -> service.getMeetings(null, 999L, 2L, null, null, null, null),
                AiChatErrorCode.RECORDING_SESSION_ACCESS_DENIED);
    }

    @Test
    @DisplayName("녹음 세션으로도 다른 팀 회의의 전사·요약은 조회할 수 없다")
    void rejectsOtherTeamMeetingWithRecordingSession() {
        Team otherTeam = withId(Team.create("다른 팀"), 3L);
        Meeting otherMeeting = withId(Meeting.create(otherTeam, member(otherTeam), "남의 회의", "테스트", null, NOW, 30), 300L);
        when(meetings.findByIdAndDeletedAtIsNull(300L)).thenReturn(Optional.of(otherMeeting));

        assertCode(() -> service.getTranscripts(null, 700L, 300L, null), MeetingErrorCode.MEETING_ACCESS_DENIED);
        assertCode(() -> service.getLatestSummary(null, 700L, 300L), MeetingErrorCode.MEETING_ACCESS_DENIED);
    }

    @Test
    @DisplayName("AI 요청 ID와 녹음 세션 ID가 둘 다 오거나 둘 다 없으면 거절한다")
    void rejectsAmbiguousIdentifiers() {
        assertCode(() -> service.getMeetings(900L, 700L, 2L, null, null, null, null),
                AiChatErrorCode.INTERNAL_QUERY_IDENTIFIER_INVALID);
        assertCode(() -> service.getTranscripts(null, null, 100L, null),
                AiChatErrorCode.INTERNAL_QUERY_IDENTIFIER_INVALID);
    }

    @Test
    @DisplayName("권한 없는 AI 요청 ID에 유효한 녹음 세션을 함께 보내도 우회할 수 없다")
    void doesNotBypassFailedChatCheckWithRecordingSession() {
        chatRequest.markFailed(AiFailureReason.AI_CALL_FAILED);

        assertCode(() -> service.getTranscripts(900L, 700L, 100L, null),
                AiChatErrorCode.INTERNAL_QUERY_IDENTIFIER_INVALID);
    }

    private void assertCode(Runnable action, BaseCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }
}
