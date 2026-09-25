package com.backend.meety.domain.meeting.service;

import static com.backend.meety.domain.recording.RecordingFixtures.credit;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditSourceType;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.dto.SummaryCreateResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.SummaryErrorCode;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import com.backend.meety.global.exception.BaseCode;
import com.backend.meety.global.exception.BusinessException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;

class MeetingSummaryServiceTest {

    private static final String IDEMPOTENCY_KEY = "11111111-2222-3333-4444-555555555555";
    private static final List<AiRequestStatus> PROCESSING = List.of(
            AiRequestStatus.ACCEPTED, AiRequestStatus.PROCESSING);

    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
    private final MeetingSummaryRepository summaries = mock(MeetingSummaryRepository.class);
    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final TeamCreditRepository credits = mock(TeamCreditRepository.class);
    private final CreditLedgerRepository ledgers = mock(CreditLedgerRepository.class);
    private final MeetingSummaryService service = new MeetingSummaryService(
            meetings, members, transcripts, summaries, aiRequests, credits, ledgers);

    private Team team;
    private TeamMember member;
    private Meeting meeting;
    private TeamCredit credit;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
        meeting = meeting(team, member);
        meeting.start(LocalDateTime.now());
        meeting.complete(LocalDateTime.now());
        credit = credit(team, 10L);
        when(aiRequests.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));
        when(transcripts.existsByMeetingId(100L)).thenReturn(true);
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(credit));
        when(aiRequests.save(any(AiRequest.class))).thenAnswer(call -> withId(call.getArgument(0), 900L));
        when(summaries.countByMeetingId(100L)).thenReturn(1L);
        when(summaries.saveAndFlush(any(MeetingSummary.class)))
                .thenAnswer(call -> withId(call.getArgument(0), 502L));
    }

    @Test
    @DisplayName("재생성이 접수되면 크레딧을 차감하고 회차 행을 만든다")
    void regenerateSucceeds() {
        SummaryCreateResponse response = service.requestSummary(1L, 100L, IDEMPOTENCY_KEY);

        assertThat(response.summaryId()).isEqualTo(502L);
        assertThat(response.version()).isEqualTo(2L);
        assertThat(response.status()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(response.creditBalance()).isEqualTo(10L - CreditPolicy.SUMMARY_REGENERATE_COST);
    }

    @Test
    @DisplayName("원장에 USE:AI_SUMMARY:{aiRequestId} 멱등키가 기록된다")
    void recordsLedgerWithIdempotencyKey() {
        service.requestSummary(1L, 100L, IDEMPOTENCY_KEY);

        ArgumentCaptor<CreditLedger> captor = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers).save(captor.capture());
        CreditLedger ledger = captor.getValue();
        assertThat(ledger.getIdempotencyKey()).isEqualTo("USE:AI_SUMMARY:900");
        assertThat(ledger.getType()).isEqualTo(CreditTransactionType.USE);
        assertThat(ledger.getSourceType()).isEqualTo(CreditSourceType.AI_SUMMARY);
        assertThat(ledger.getAmount()).isEqualTo(-CreditPolicy.SUMMARY_REGENERATE_COST);
    }

    @Test
    @DisplayName("생성되는 AiRequest는 SUMMARY 타입에 ACCEPTED 상태다")
    void createsAcceptedSummaryRequest() {
        service.requestSummary(1L, 100L, IDEMPOTENCY_KEY);

        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiRequests).save(captor.capture());
        AiRequest request = captor.getValue();
        assertThat(request.getRequestType()).isEqualTo(AiRequestType.SUMMARY);
        assertThat(request.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(request.getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);
    }

    @Test
    @DisplayName("같은 Idempotency-Key 재전송이면 기존 접수 결과를 그대로 반환한다")
    void replaysSameIdempotencyKey() {
        AiRequest existing = withId(AiRequest.create(team, member, IDEMPOTENCY_KEY, AiRequestType.SUMMARY), 900L);
        MeetingSummary existingSummary = withId(
                MeetingSummary.createPending(existing, team, meeting, 2L), 502L);
        when(aiRequests.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));
        when(summaries.findByAiRequestId(900L)).thenReturn(Optional.of(existingSummary));
        when(credits.findByTeamIdAndDeletedAtIsNull(2L)).thenReturn(Optional.of(credit));

        SummaryCreateResponse response = service.requestSummary(1L, 100L, IDEMPOTENCY_KEY);

        assertThat(response.summaryId()).isEqualTo(502L);
        verify(aiRequests, never()).save(any());
        verify(ledgers, never()).save(any());
    }

    @Test
    @DisplayName("회의가 없으면 404다")
    void rejectsMissingMeeting() {
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

        assertCode(() -> service.requestSummary(1L, 100L, IDEMPOTENCY_KEY), MeetingErrorCode.MEETING_NOT_FOUND);
    }

    @Test
    @DisplayName("활성 팀원이 아니면 403이다")
    void rejectsNonTeamMember() {
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertCode(() -> service.requestSummary(1L, 100L, IDEMPOTENCY_KEY), MeetingErrorCode.MEETING_ACCESS_DENIED);
    }

    @Test
    @DisplayName("종료되지 않은 회의면 409다")
    void rejectsNotCompletedMeeting() {
        Meeting waiting = meeting(team, member);
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(waiting));

        assertCode(() -> service.requestSummary(1L, 100L, IDEMPOTENCY_KEY), SummaryErrorCode.MEETING_NOT_COMPLETED);
        verify(aiRequests, never()).save(any());
    }

    @Test
    @DisplayName("전사가 없으면 크레딧 차감 없이 409다")
    void rejectsEmptyTranscript() {
        when(transcripts.existsByMeetingId(100L)).thenReturn(false);

        assertCode(() -> service.requestSummary(1L, 100L, IDEMPOTENCY_KEY), SummaryErrorCode.TRANSCRIPT_EMPTY);
        verify(credits, never()).findByTeamIdForUpdate(anyLong());
        assertThat(credit.getBalance()).isEqualTo(10L);
    }

    @Test
    @DisplayName("이미 생성 중인 요약이 있으면 409다")
    void rejectsAlreadyProcessing() {
        when(summaries.existsByMeetingIdAndAiRequestStatusIn(100L, PROCESSING)).thenReturn(true);

        assertCode(() -> service.requestSummary(1L, 100L, IDEMPOTENCY_KEY), SummaryErrorCode.SUMMARY_ALREADY_PROCESSING);
        verify(credits, never()).findByTeamIdForUpdate(anyLong());
    }

    @Test
    @DisplayName("크레딧이 부족하면 409다")
    void rejectsInsufficientCredit() {
        TeamCredit poor = credit(team, CreditPolicy.SUMMARY_REGENERATE_COST - 1);
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(poor));

        assertCode(() -> service.requestSummary(1L, 100L, IDEMPOTENCY_KEY), CreditErrorCode.INSUFFICIENT_CREDIT);
        verify(aiRequests, never()).save(any());
    }

    @Test
    @DisplayName("동시 요청으로 UNIQUE(meeting_id, version)에 걸리면 409로 변환한다")
    void translatesUniqueViolationToAlreadyProcessing() {
        when(summaries.saveAndFlush(any(MeetingSummary.class)))
                .thenThrow(new DataIntegrityViolationException("uk_meeting_summaries_meeting_id_version"));

        assertCode(() -> service.requestSummary(1L, 100L, IDEMPOTENCY_KEY), SummaryErrorCode.SUMMARY_ALREADY_PROCESSING);
    }

    private void assertCode(Runnable action, BaseCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }
}
