package com.backend.meety.domain.meeting.service;

import static com.backend.meety.domain.recording.RecordingFixtures.credit;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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
import com.backend.meety.domain.ai.service.AiProcessingAttempt;
import com.backend.meety.domain.credit.TestCreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.event.CreditEarnedEvent;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.MetricPolicy;
import com.backend.meety.domain.meeting.SummaryPolicy;
import com.backend.meety.domain.meeting.event.SummaryReadyEvent;
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
import org.springframework.context.ApplicationEventPublisher;

class SummaryProcessingServiceTest {

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final MeetingSummaryRepository summaries = mock(MeetingSummaryRepository.class);
    private final TeamCreditRepository credits = mock(TeamCreditRepository.class);
    private final CreditLedgerRepository ledgers = mock(CreditLedgerRepository.class);
    private final TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final SummaryProcessingService service = new SummaryProcessingService(
            aiRequests, summaries, credits, ledgers, TestCreditPolicy.DEFAULT,
            new SummaryAiRequestFactory(transcripts), eventPublisher);

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
        summary = withId(MeetingSummary.createPending(aiRequest, team, meeting, 1L, null), 502L);
        when(aiRequests.claimAccepted(eq(900L), anyString())).thenAnswer(invocation -> {
            if (!aiRequest.isAccepted()) {
                return 0;
            }
            aiRequest.markProcessing();
            return 1;
        });
        when(aiRequests.findById(900L)).thenReturn(Optional.of(aiRequest));
        when(summaries.findByAiRequestId(900L)).thenReturn(Optional.of(summary));
        when(transcripts.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of());
        when(aiRequests.completeProcessing(anyLong(), anyString())).thenAnswer(invocation -> {
            AiRequest request = aiRequests.findById(invocation.getArgument(0)).orElseThrow();
            request.markCompleted();
            return 1;
        });
        when(aiRequests.retryProcessing(900L, "token-a", SummaryPolicy.MAX_RETRY_COUNT - 1))
                .thenAnswer(invocation -> {
                    if (aiRequest.getRetryCount() >= SummaryPolicy.MAX_RETRY_COUNT - 1) {
                        return 0;
                    }
                    aiRequest.increaseRetryCount();
                    aiRequest.markAccepted();
                    return 1;
                });
        when(aiRequests.failRetryExhaustedProcessing(
                900L, "token-a", SummaryPolicy.MAX_RETRY_COUNT - 1, SummaryPolicy.MAX_RETRY_COUNT))
                .thenAnswer(invocation -> {
                    if (aiRequest.getRetryCount() < SummaryPolicy.MAX_RETRY_COUNT - 1) {
                        return 0;
                    }
                    if (aiRequest.getRetryCount() < SummaryPolicy.MAX_RETRY_COUNT) {
                        aiRequest.increaseRetryCount();
                    }
                    aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
                    return 1;
                });
        when(aiRequests.failProcessing(eq(900L), anyString())).thenAnswer(invocation -> {
            aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
            return 1;
        });
    }

    @Test
    @DisplayName("ACCEPTED 요청을 PROCESSING으로 전이하고 AI 요청 본문을 만든다")
    void startProcessingTransitionsAndBuildsPayload() {
        // 테스트 목적:
        // ACCEPTED 요약 요청의 원자적 선점에 성공하면
        // PROCESSING 상태와 AI 요청 본문이 함께 준비되는지 검증한다.

        // given

        // when
        Optional<AiProcessingAttempt<SummaryAiRequest>> request = service.startProcessing(900L);

        // then
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(request).isPresent();
        assertThat(request.get().processingToken()).isNotBlank();
        assertThat(request.get().payload().requestId()).isEqualTo("900");
        assertThat(request.get().payload().meetingId()).isEqualTo(100L);
        assertThat(request.get().payload().speakers()).isEmpty();
    }

    @Test
    @DisplayName("다른 인스턴스가 먼저 선점한 요청은 건너뛴다")
    void startProcessingSkipsClaimMiss() {
        // 테스트 목적:
        // 동일 요청을 다른 인스턴스가 먼저 선점해 조건부 UPDATE가 0을 반환하면
        // 예외나 AI 요청 생성 없이 정상적으로 건너뛰는지 검증한다.

        // given
        doReturn(0).when(aiRequests).claimAccepted(eq(900L), anyString());

        // when
        Optional<AiProcessingAttempt<SummaryAiRequest>> result = service.startProcessing(900L);

        // then
        assertThat(result).isEmpty();
        verify(aiRequests, never()).findById(anyLong());
        verify(summaries, never()).findByAiRequestId(anyLong());
    }

    @Test
    @DisplayName("선점 후 요약 행이 없으면 요청을 FAILED로 처리한다")
    void startProcessingFailsWhenSummaryMissing() {
        // 테스트 목적:
        // 원자적 선점에는 성공했지만 연결된 MeetingSummary가 없으면
        // 기존 정책대로 AI 호출 대상에서 제외하고 FAILED로 처리하는지 검증한다.

        // given
        when(summaries.findByAiRequestId(900L)).thenReturn(Optional.empty());

        // when
        Optional<AiProcessingAttempt<SummaryAiRequest>> result = service.startProcessing(900L);

        // then
        assertThat(result).isEmpty();
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
    }

    @Test
    @DisplayName("재시도로 ACCEPTED가 된 요청은 다시 선점할 수 있다")
    void retryableRequestCanBeClaimedAgain() {
        // 테스트 목적:
        // AI 호출 실패로 ACCEPTED 상태에 재등록된 요약 요청이
        // 다음 폴링에서 동일한 원자적 선점 절차를 다시 통과하는지 검증한다.

        // given
        aiRequest.markProcessing();
        service.handleRetryableFailure(900L, "token-a");

        // when
        Optional<AiProcessingAttempt<SummaryAiRequest>> result = service.startProcessing(900L);

        // then
        assertThat(result).isPresent();
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(aiRequest.getRetryCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("완료하면 content를 채우고 COMPLETED로 전이한다")
    void completeProcessingFillsContent() {
        aiRequest.markProcessing();

        service.completeProcessing(900L, "token-a", "## 요약");

        assertThat(summary.getContent()).isEqualTo("## 요약");
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
    }

    @Test
    @DisplayName("최초 자동 요약 성공 시 METRIC 요청을 등록한다")
    void completeFirstSummaryRegistersMetricRequest() {
        // 테스트 목적:
        // 최초 자동 요약이 정상 완료된 경우
        // 별도 HTTP 호출 없이 Metric 처리용 AiRequest가 등록되는지 검증한다.

        // given
        AiRequest firstRequest = withId(AiRequest.create(
                team, member, SummaryPolicy.firstSummaryIdempotencyKey(meeting.getId()), AiRequestType.SUMMARY), 901L);
        MeetingSummary firstSummary = withId(
                MeetingSummary.createPending(firstRequest, team, meeting, 1L, null), 503L);
        firstRequest.markProcessing();
        when(aiRequests.findById(901L)).thenReturn(Optional.of(firstRequest));
        when(summaries.findByAiRequestId(901L)).thenReturn(Optional.of(firstSummary));

        // when
        service.completeProcessing(901L, "token-a", "## 최초 요약");

        // then
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiRequests).save(captor.capture());
        AiRequest metricRequest = captor.getValue();
        assertThat(metricRequest.getRequestType()).isEqualTo(AiRequestType.METRIC);
        assertThat(metricRequest.getIdempotencyKey()).isEqualTo(MetricPolicy.idempotencyKey(503L));
        assertThat(metricRequest.getTeam()).isSameAs(team);
        assertThat(metricRequest.getTeamMember()).isSameAs(member);
    }

    @Test
    @DisplayName("요약 재생성 성공 시 새 METRIC 요청을 등록한다")
    void completeRegeneratedSummaryRegistersMetricRequest() {
        // 테스트 목적:
        // 수동 요약 재생성이 정상 완료된 경우
        // 재생성된 요약 버전을 기준으로 신규 Metric 요청이 등록되는지 검증한다.

        // given
        aiRequest.markProcessing();

        // when
        service.completeProcessing(900L, "token-a", "## 재생성 요약");

        // then
        ArgumentCaptor<AiRequest> captor = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiRequests).save(captor.capture());
        AiRequest metricRequest = captor.getValue();
        assertThat(metricRequest.getRequestType()).isEqualTo(AiRequestType.METRIC);
        assertThat(metricRequest.getIdempotencyKey()).isEqualTo(MetricPolicy.idempotencyKey(502L));
    }

    @Test
    @DisplayName("최초 자동 요약 완료는 알림 이벤트를 발행하지 않는다")
    void completeProcessingSkipsNotificationForFirstSummary() {
        // 테스트 목적:
        // 최초 자동 요약의 서버 유도 멱등키로 완료된 요약은
        // SUMMARY_READY 알림 이벤트 발행 대상에서 제외되는지 검증한다.

        // given
        AiRequest firstRequest = withId(AiRequest.create(
                team, member, SummaryPolicy.firstSummaryIdempotencyKey(meeting.getId()), AiRequestType.SUMMARY), 901L);
        MeetingSummary firstSummary = withId(
                MeetingSummary.createPending(firstRequest, team, meeting, 1L, null), 503L);
        firstRequest.markProcessing();
        when(aiRequests.findById(901L)).thenReturn(Optional.of(firstRequest));
        when(summaries.findByAiRequestId(901L)).thenReturn(Optional.of(firstSummary));

        // when
        service.completeProcessing(901L, "token-a", "## 최초 요약");

        // then
        assertThat(firstSummary.getContent()).isEqualTo("## 최초 요약");
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("수동 재생성 요약 완료는 알림 이벤트를 발행한다")
    void completeProcessingPublishesNotificationForRegeneratedSummary() {
        // 테스트 목적:
        // 최초 자동 요약 멱등키가 아닌 수동 재생성 요청이 정상 완료된 경우
        // SUMMARY_READY 알림 생성을 위한 이벤트가 발행되는지 검증한다.

        // given
        aiRequest.markProcessing();

        // when
        service.completeProcessing(900L, "token-a", "## 재생성 요약");

        // then
        verify(eventPublisher).publishEvent(new SummaryReadyEvent(2L, 100L, 900L, "회의"));
    }

    @Test
    @DisplayName("요약 재생성이 실패하면 알림 이벤트를 발행하지 않는다")
    void summaryFailureDoesNotPublishNotification() {
        // 테스트 목적:
        // 수동 재생성 요청이 완료 흐름에 도달하지 못하고 실패 처리되는 경우
        // SUMMARY_READY 알림 이벤트가 발행되지 않는지 검증한다.

        // given
        aiRequest.markProcessing();

        // when
        service.handlePermanentFailure(900L, "token-a");

        // then
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        verify(eventPublisher, never()).publishEvent(any());
        verify(aiRequests, never()).save(any());
    }

    @Test
    @DisplayName("재시도 한도 미만 실패면 ACCEPTED로 되돌린다")
    void handleFailureRetries() {
        aiRequest.markProcessing();

        service.handleRetryableFailure(900L, "token-a");

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

        service.handleRetryableFailure(900L, "token-a");

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
        assertThat(teamCredit.getBalance()).isEqualTo(980L);
        ArgumentCaptor<CreditLedger> captor = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("RESTORE:AI_SUMMARY:900");
        assertThat(captor.getValue().getType()).isEqualTo(CreditTransactionType.RESTORE);
        assertThat(captor.getValue().getAmount()).isEqualTo(TestCreditPolicy.SUMMARY_REGENERATE_COST);
        verify(eventPublisher, never()).publishEvent(any(CreditEarnedEvent.class));
    }

    @Test
    @DisplayName("즉시 실패 처리는 재시도 없이 FAILED로 확정하고 크레딧을 복구한다")
    void permanentFailureSkipsRetry() {
        aiRequest.markProcessing();
        TeamCredit teamCredit = credit(team, 977L);
        when(ledgers.existsByIdempotencyKey("USE:AI_SUMMARY:900")).thenReturn(true);
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(teamCredit));

        service.handlePermanentFailure(900L, "token-a");

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getRetryCount()).isZero();
        assertThat(teamCredit.getBalance()).isEqualTo(980L);
    }

    @Test
    @DisplayName("stale Summary 최종 실패는 기존 크레딧 복구 정책을 유지한다")
    void staleFailureRestoresCredit() {
        // 테스트 목적:
        // stale 복구 쿼리가 Summary를 FAILED로 전이한 경우에도
        // 수동 요약 재생성에서 차감한 크레딧이 동일하게 복구되는지 검증한다.

        // given
        aiRequest.markProcessing();
        aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
        TeamCredit teamCredit = credit(team, 977L);
        when(ledgers.existsByIdempotencyKey("USE:AI_SUMMARY:900")).thenReturn(true);
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(teamCredit));

        // when
        service.restoreCreditAfterStaleFailure(900L);

        // then
        assertThat(teamCredit.getBalance()).isEqualTo(980L);
        ArgumentCaptor<CreditLedger> captor = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers).save(captor.capture());
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("RESTORE:AI_SUMMARY:900");
    }

    @Test
    @DisplayName("무료 첫 요약(차감 원장 없음)은 최종 실패해도 복구하지 않는다")
    void handleFailureSkipsRestoreWhenNotCharged() {
        aiRequest.markProcessing();
        aiRequest.increaseRetryCount();
        aiRequest.increaseRetryCount();
        when(ledgers.existsByIdempotencyKey("USE:AI_SUMMARY:900")).thenReturn(false);

        service.handleRetryableFailure(900L, "token-a");

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        verify(credits, never()).findByTeamIdForUpdate(anyLong());
        verify(ledgers, never()).save(any());
    }

    @Test
    @DisplayName("이전 Attempt의 늦은 요약 성공 응답은 최신 요약 결과를 덮어쓰지 않는다")
    void lateSuccessDoesNotOverwriteCurrentSummary() {
        // 테스트 목적:
        // 재시도 Attempt가 저장한 최신 요약이 있는 상태에서 이전 token의 성공 응답이 도착해도
        // 요약 본문과 후속 Metric 요청이 변경되지 않는지 검증한다.

        // given
        aiRequest.markProcessing();
        summary.complete("## 최신 요약");
        doReturn(0).when(aiRequests).completeProcessing(900L, "old-token");

        // when
        service.completeProcessing(900L, "old-token", "## 오래된 요약");

        // then
        assertThat(summary.getContent()).isEqualTo("## 최신 요약");
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        verify(aiRequests, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("이전 Attempt의 늦은 요약 실패 응답은 최신 Attempt를 변경하지 않는다")
    void lateFailureDoesNotChangeCurrentSummaryAttempt() {
        // 테스트 목적:
        // 최신 Attempt가 PROCESSING인 동안 이전 token의 실패 응답이 도착해도
        // retryCount, 상태, 크레딧이 변경되지 않는지 검증한다.

        // given
        aiRequest.markProcessing();

        // when
        service.handleRetryableFailure(900L, "old-token");

        // then
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(aiRequest.getRetryCount()).isZero();
        verify(credits, never()).findByTeamIdForUpdate(anyLong());
        verify(ledgers, never()).save(any());
    }
}
