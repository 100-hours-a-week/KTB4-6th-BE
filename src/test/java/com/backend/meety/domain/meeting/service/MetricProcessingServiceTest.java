package com.backend.meety.domain.meeting.service;

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

import com.backend.meety.domain.ai.client.MetricAiAgenda;
import com.backend.meety.domain.ai.client.MetricAiOffTopic;
import com.backend.meety.domain.ai.client.MetricAiRequest;
import com.backend.meety.domain.ai.client.MetricAiResponse;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.meeting.MetricPolicy;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingMetric;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.meeting.repository.MeetingMetricRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.ObjectMapper;

class MetricProcessingServiceTest {

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final MeetingSummaryRepository summaries = mock(MeetingSummaryRepository.class);
    private final MeetingMetricRepository metrics = mock(MeetingMetricRepository.class);
    private final TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
    private final MetricProcessingService service = new MetricProcessingService(
            aiRequests, summaries, metrics, new MetricAiRequestFactory(transcripts), new ObjectMapper());

    private Team team;
    private TeamMember member;
    private Meeting meeting;
    private AiRequest summaryRequest;
    private AiRequest metricRequest;
    private MeetingSummary summary;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
        meeting = meeting(team, member);
        meeting.start(LocalDateTime.now());
        meeting.complete(LocalDateTime.now());
        summaryRequest = withId(AiRequest.create(team, member, "summary-key", AiRequestType.SUMMARY), 800L);
        metricRequest = withId(
                AiRequest.create(team, member, MetricPolicy.idempotencyKey(502L), AiRequestType.METRIC), 900L);
        summary = withId(MeetingSummary.createPending(summaryRequest, team, meeting, 2L, null), 502L);
        summary.complete("# 회의 요약");
        when(aiRequests.findById(900L)).thenReturn(Optional.of(metricRequest));
        when(summaries.findById(502L)).thenReturn(Optional.of(summary));
        when(transcripts.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(segment(101L, 0L)));
    }

    @Test
    @DisplayName("ACCEPTED Metric 요청을 PROCESSING으로 전이하고 AI 요청 본문을 만든다")
    void startProcessingBuildsPayload() {
        // 테스트 목적:
        // Metric 스케줄러가 선점한 요청만 처리하도록 상태를 PROCESSING으로 바꾸고
        // 연결된 요약 버전의 본문으로 AI 요청 본문을 만드는지 검증한다.

        // given

        // when
        Optional<MetricAiRequest> result = service.startProcessing(900L);

        // then
        assertThat(result).isPresent();
        assertThat(metricRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(result.get().requestId()).isEqualTo("900");
        assertThat(result.get().summary()).isEqualTo("# 회의 요약");
        assertThat(result.get().segments()).hasSize(1);
    }

    @Test
    @DisplayName("요약을 찾을 수 없으면 Metric 요청을 FAILED로 처리하고 AI를 호출하지 않는다")
    void startProcessingFailsWhenSummaryMissing() {
        // 테스트 목적:
        // Metric 요청의 멱등키가 가리키는 요약 버전이 없으면
        // 잘못된 요청으로 보고 후속 AI 호출 없이 실패 처리하는지 검증한다.

        // given
        when(summaries.findById(502L)).thenReturn(Optional.empty());

        // when
        Optional<MetricAiRequest> result = service.startProcessing(900L);

        // then
        assertThat(result).isEmpty();
        assertThat(metricRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(metricRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
    }

    @Test
    @DisplayName("Metric 응답을 검증한 뒤 신규 MeetingMetric으로 저장한다")
    void completeProcessingSavesMetric() {
        // 테스트 목적:
        // AI 응답의 requestId와 meetingId가 요청과 일치하면
        // offTopic 시간과 agendas JSON을 원본 밀리초 단위 그대로 저장하는지 검증한다.

        // given
        metricRequest.markProcessing();
        MetricAiResponse response = new MetricAiResponse(
                "900", 100L, new MetricAiOffTopic(0L), List.of(new MetricAiAgenda("배포 일정", 2500L)));

        // when
        service.completeProcessing(900L, response);

        // then
        ArgumentCaptor<MeetingMetric> captor = ArgumentCaptor.forClass(MeetingMetric.class);
        verify(metrics).save(captor.capture());
        MeetingMetric metric = captor.getValue();
        assertThat(metricRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
        assertThat(metric.getAiRequest()).isSameAs(metricRequest);
        assertThat(metric.getMeeting()).isSameAs(meeting);
        assertThat(metric.getContextOutDurationMs()).isZero();
        assertThat(metric.getMetrics()).contains("\"agendas\"");
        assertThat(metric.getMetrics()).contains("\"title\":\"배포 일정\"");
        assertThat(metric.getMetrics()).contains("\"durationMs\":2500");
    }

    @Test
    @DisplayName("agendas와 offTopic이 null이면 null을 보존해 저장한다")
    void completeProcessingPreservesNullValues() {
        // 테스트 목적:
        // AI가 agendas 또는 offTopic.durationMs를 null로 응답한 경우
        // 0으로 보정하지 않고 null과 0의 의미 차이를 보존하는지 검증한다.

        // given
        metricRequest.markProcessing();
        MetricAiResponse response = new MetricAiResponse("900", 100L, null, null);

        // when
        service.completeProcessing(900L, response);

        // then
        ArgumentCaptor<MeetingMetric> captor = ArgumentCaptor.forClass(MeetingMetric.class);
        verify(metrics).save(captor.capture());
        assertThat(captor.getValue().getContextOutDurationMs()).isNull();
        assertThat(captor.getValue().getMetrics()).isEqualTo("{\"agendas\":null}");
    }

    @Test
    @DisplayName("빈 agendas는 빈 배열로 저장한다")
    void completeProcessingPreservesEmptyAgendas() {
        // 테스트 목적:
        // AI가 분석 가능한 안건이 없다고 빈 배열을 응답한 경우
        // null로 바꾸지 않고 빈 배열 그대로 저장하는지 검증한다.

        // given
        metricRequest.markProcessing();
        MetricAiResponse response = new MetricAiResponse("900", 100L, new MetricAiOffTopic(null), List.of());

        // when
        service.completeProcessing(900L, response);

        // then
        ArgumentCaptor<MeetingMetric> captor = ArgumentCaptor.forClass(MeetingMetric.class);
        verify(metrics).save(captor.capture());
        assertThat(captor.getValue().getContextOutDurationMs()).isNull();
        assertThat(captor.getValue().getMetrics()).isEqualTo("{\"agendas\":[]}");
    }

    @Test
    @DisplayName("응답 requestId가 다르면 저장하지 않고 FAILED로 처리한다")
    void completeProcessingRejectsMismatchedRequestId() {
        // 테스트 목적:
        // AI 응답 requestId가 처리 중인 Metric 요청 ID와 다르면
        // 다른 요청의 결과로 보고 저장하지 않는지 검증한다.

        // given
        metricRequest.markProcessing();
        MetricAiResponse response = new MetricAiResponse("901", 100L, new MetricAiOffTopic(1L), List.of());

        // when
        service.completeProcessing(900L, response);

        // then
        assertThat(metricRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        verify(metrics, never()).save(any());
    }

    @Test
    @DisplayName("응답 meetingId가 다르면 저장하지 않고 FAILED로 처리한다")
    void completeProcessingRejectsMismatchedMeetingId() {
        // 테스트 목적:
        // AI 응답 meetingId가 요약이 속한 회의와 다르면
        // 오래되거나 잘못 라우팅된 결과로 보고 저장하지 않는지 검증한다.

        // given
        metricRequest.markProcessing();
        MetricAiResponse response = new MetricAiResponse("900", 101L, new MetricAiOffTopic(1L), List.of());

        // when
        service.completeProcessing(900L, response);

        // then
        assertThat(metricRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        verify(metrics, never()).save(any());
    }

    @Test
    @DisplayName("이미 저장된 Metric 응답은 중복 저장하지 않고 완료 상태만 보존한다")
    void completeProcessingSkipsDuplicateResponse() {
        // 테스트 목적:
        // 같은 aiRequestId에 대한 Metric 행이 이미 존재하면
        // 중복 응답을 새 이력으로 저장하지 않는지 검증한다.

        // given
        metricRequest.markProcessing();
        when(metrics.existsByAiRequestId(900L)).thenReturn(true);
        MetricAiResponse response = new MetricAiResponse("900", 100L, new MetricAiOffTopic(1L), List.of());

        // when
        service.completeProcessing(900L, response);

        // then
        assertThat(metricRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
        verify(metrics, never()).save(any());
    }

    @Test
    @DisplayName("요약 재생성 Metric은 기존 성공 Metric을 덮어쓰지 않고 새 행으로 저장한다")
    void regeneratedSummaryMetricCreatesNewMetricRow() {
        // 테스트 목적:
        // 요약 재생성 후 생성된 별도 Metric 요청이 완료되면
        // 이전 Metric 이력을 삭제하거나 덮어쓰지 않고 새 aiRequestId로 저장하는지 검증한다.

        // given
        MeetingSummary regeneratedSummary = withId(
                MeetingSummary.createPending(summaryRequest, team, meeting, 3L, "다시"), 503L);
        regeneratedSummary.complete("# 재생성 요약");
        AiRequest regeneratedMetricRequest = withId(
                AiRequest.create(team, member, MetricPolicy.idempotencyKey(503L), AiRequestType.METRIC), 901L);
        regeneratedMetricRequest.markProcessing();
        when(aiRequests.findById(901L)).thenReturn(Optional.of(regeneratedMetricRequest));
        when(summaries.findById(503L)).thenReturn(Optional.of(regeneratedSummary));
        when(metrics.existsByAiRequestId(901L)).thenReturn(false);
        MetricAiResponse response = new MetricAiResponse(
                "901", 100L, new MetricAiOffTopic(2000L), List.of(new MetricAiAgenda("품질", 3000L)));

        // when
        service.completeProcessing(901L, response);

        // then
        ArgumentCaptor<MeetingMetric> captor = ArgumentCaptor.forClass(MeetingMetric.class);
        verify(metrics).save(captor.capture());
        assertThat(captor.getValue().getAiRequest()).isSameAs(regeneratedMetricRequest);
        assertThat(captor.getValue().getMetrics()).contains("\"title\":\"품질\"");
        assertThat(regeneratedMetricRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
    }

    @Test
    @DisplayName("재시도 한도 미만 실패면 ACCEPTED로 되돌린다")
    void retryableFailureRequeuesRequest() {
        // 테스트 목적:
        // 일시적인 AI Metric 호출 실패가 발생하면
        // 재시도 한도 전까지 요청을 다시 폴링 대상으로 돌리는지 검증한다.

        // given
        metricRequest.markProcessing();

        // when
        service.handleRetryableFailure(900L);

        // then
        assertThat(metricRequest.getStatus()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(metricRequest.getRetryCount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("재시도 한도에 도달하면 FAILED로 확정한다")
    void retryableFailureFailsAfterRetryLimit() {
        // 테스트 목적:
        // AI Metric 호출 실패가 재시도 한도에 도달하면
        // 기존 Summary 성공 상태와 별개로 Metric 요청만 최종 실패 처리되는지 검증한다.

        // given
        metricRequest.markProcessing();
        metricRequest.increaseRetryCount();
        metricRequest.increaseRetryCount();

        // when
        service.handleRetryableFailure(900L);

        // then
        assertThat(metricRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(metricRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
    }

    private TranscriptSegment segment(Long id, Long sequenceNumber) {
        return withId(TranscriptSegment.createFinal(
                meeting, "segment-" + id, sequenceNumber, "발언 " + id, 1000L, 2000L, LocalDateTime.now()), id);
    }
}
