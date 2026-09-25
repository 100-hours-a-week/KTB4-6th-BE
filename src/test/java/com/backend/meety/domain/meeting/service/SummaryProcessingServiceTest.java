package com.backend.meety.domain.meeting.service;

import static com.backend.meety.domain.recording.RecordingFixtures.credit;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.SummaryAiRequest;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SummaryProcessingServiceTest {

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final MeetingSummaryRepository summaries = mock(MeetingSummaryRepository.class);
    private final TeamCreditRepository credits = mock(TeamCreditRepository.class);
    private final CreditLedgerRepository ledgers = mock(CreditLedgerRepository.class);
    private final TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
    private final SummaryProcessingService service = new SummaryProcessingService(
            aiRequests, summaries, credits, ledgers, new SummaryAiRequestFactory(transcripts));

    private Team team;
    private TeamMember member;
    private Meeting meeting;
    private AiRequest aiRequest;
    private MeetingSummary summary;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
        meeting = meeting(team, member);
        meeting.start(LocalDateTime.now());
        meeting.complete(LocalDateTime.now());
        aiRequest = withId(AiRequest.create(team, member, "key-1", AiRequestType.SUMMARY), 900L);
        summary = withId(MeetingSummary.createPending(aiRequest, team, meeting, 1L), 502L);
        when(aiRequests.findById(900L)).thenReturn(Optional.of(aiRequest));
        when(summaries.findByAiRequestId(900L)).thenReturn(Optional.of(summary));
        when(transcripts.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of());
    }

    @Test
    @DisplayName("ACCEPTED 요청을 PROCESSING으로 전이하고 AI 요청 본문을 만든다")
    void startProcessingTransitionsAndBuildsPayload() {
        Optional<SummaryAiRequest> request = service.startProcessing(900L);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(request).isPresent();
        assertThat(request.get().requestId()).isEqualTo("900");
        assertThat(request.get().meetingId()).isEqualTo(100L);
        assertThat(request.get().speakers()).isEmpty();
    }

    @Test
    @DisplayName("이미 PROCESSING이면 건너뛴다")
    void startProcessingSkipsNonAccepted() {
        aiRequest.markProcessing();

        assertThat(service.startProcessing(900L)).isEmpty();
    }

    @Test
    @DisplayName("완료하면 content를 채우고 COMPLETED로 전이한다")
    void completeProcessingFillsContent() {
        aiRequest.markProcessing();

        service.completeProcessing(900L, "## 요약");

        assertThat(summary.getContent()).isEqualTo("## 요약");
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
    }

    @Test
    @DisplayName("재시도 한도 미만 실패면 ACCEPTED로 되돌린다")
    void handleFailureRetries() {
        aiRequest.markProcessing();

        service.handleRetryableFailure(900L);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(aiRequest.getRetryCount()).isEqualTo(1L);
        verify(ledgers, never()).save(any());
    }

    @Test
    @DisplayName("3회째 실패면 FAILED로 확정하고 차감된 크레딧을 복구한다")
    void handleFailureRestoresCreditOnFinalFailure() {
        aiRequest.markProcessing();
        aiRequest.increaseRetryCount();
        aiRequest.increaseRetryCount();
        TeamCredit teamCredit = credit(team, 977L);
        when(ledgers.existsByIdempotencyKey("USE:AI_SUMMARY:900")).thenReturn(true);
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(teamCredit));

        service.handleRetryableFailure(900L);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
        assertThat(teamCredit.getBalance()).isEqualTo(980L);
        ArgumentCaptor<CreditLedger> captor = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("RESTORE:AI_SUMMARY:900");
        assertThat(captor.getValue().getType()).isEqualTo(CreditTransactionType.RESTORE);
        assertThat(captor.getValue().getAmount()).isEqualTo(CreditPolicy.SUMMARY_REGENERATE_COST);
    }

    @Test
    @DisplayName("즉시 실패 처리는 재시도 없이 FAILED로 확정하고 크레딧을 복구한다")
    void permanentFailureSkipsRetry() {
        aiRequest.markProcessing();
        TeamCredit teamCredit = credit(team, 977L);
        when(ledgers.existsByIdempotencyKey("USE:AI_SUMMARY:900")).thenReturn(true);
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(teamCredit));

        service.handlePermanentFailure(900L);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getRetryCount()).isZero();
        assertThat(teamCredit.getBalance()).isEqualTo(980L);
    }

    @Test
    @DisplayName("무료 첫 요약(차감 원장 없음)은 최종 실패해도 복구하지 않는다")
    void handleFailureSkipsRestoreWhenNotCharged() {
        aiRequest.markProcessing();
        aiRequest.increaseRetryCount();
        aiRequest.increaseRetryCount();
        when(ledgers.existsByIdempotencyKey("USE:AI_SUMMARY:900")).thenReturn(false);

        service.handleRetryableFailure(900L);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        verify(credits, never()).findByTeamIdForUpdate(anyLong());
        verify(ledgers, never()).save(any());
    }
}
