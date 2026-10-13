package com.backend.meety.domain.meeting.service;

import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.MetricAiClient;
import com.backend.meety.domain.ai.client.MetricAiOffTopic;
import com.backend.meety.domain.ai.client.MetricAiRequest;
import com.backend.meety.domain.ai.client.MetricAiResponse;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.meeting.MetricPolicy;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

class MetricProcessingSchedulerTest {

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final MetricProcessingService processingService = mock(MetricProcessingService.class);
    private final MetricAiClient aiClient = mock(MetricAiClient.class);
    private final MetricProcessingScheduler scheduler = new MetricProcessingScheduler(
            aiRequests, processingService, aiClient);

    private final MetricAiRequest payload = new MetricAiRequest("900", 100L, "# 요약", List.of());
    private final MetricAiResponse response = new MetricAiResponse("900", 100L, new MetricAiOffTopic(0L), List.of());

    private Team team;
    private TeamMember member;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
        when(aiRequests.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.METRIC, AiRequestStatus.ACCEPTED, Limit.of(MetricPolicy.SCHEDULER_BATCH_SIZE)))
                .thenReturn(List.of(request(900L)));
    }

    @Test
    @DisplayName("성공하면 Metric 결과를 저장 처리한다")
    void processesAcceptedMetric() {
        // 테스트 목적:
        // ACCEPTED 상태의 Metric 요청이 AI 호출에 성공하면
        // 응답 저장 처리를 완료 서비스에 위임하는지 검증한다.

        // given
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestMetric(payload)).thenReturn(response);

        // when
        scheduler.processAcceptedMetrics();

        // then
        verify(processingService).completeProcessing(900L, response);
        verify(processingService, never()).handleRetryableFailure(anyLong());
    }

    @Test
    @DisplayName("선점할 수 없는 요청은 AI를 호출하지 않는다")
    void skipsWhenStartProcessingRejects() {
        // 테스트 목적:
        // 다른 인스턴스나 상태 전이로 더 이상 ACCEPTED가 아닌 요청은
        // AI 호출 없이 건너뛰는지 검증한다.

        // given
        when(processingService.startProcessing(900L)).thenReturn(Optional.empty());

        // when
        scheduler.processAcceptedMetrics();

        // then
        verify(aiClient, never()).requestMetric(any());
        verify(processingService, never()).completeProcessing(anyLong(), any());
    }

    @Test
    @DisplayName("422 응답은 재시도하지 않고 최종 실패 처리한다")
    void unprocessableEntityFailsPermanently() {
        // 테스트 목적:
        // AI가 요청 데이터 오류를 의미하는 422를 반환하면
        // 같은 입력 재시도 없이 Metric 요청만 영구 실패 처리하는지 검증한다.

        // given
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestMetric(payload)).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY, "422", HttpHeaders.EMPTY, new byte[0], null));

        // when
        scheduler.processAcceptedMetrics();

        // then
        verify(processingService).handlePermanentFailure(900L);
        verify(processingService, never()).handleRetryableFailure(anyLong());
    }

    @Test
    @DisplayName("502 응답은 재시도 대상이다")
    void badGatewayIsRetryable() {
        // 테스트 목적:
        // AI LLM 처리 실패를 의미하는 502 응답은
        // 재시도 가능한 실패로 분류되는지 검증한다.

        // given
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestMetric(payload)).thenThrow(HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "502", HttpHeaders.EMPTY, new byte[0], null));

        // when
        scheduler.processAcceptedMetrics();

        // then
        verify(processingService).handleRetryableFailure(900L);
        verify(processingService, never()).handlePermanentFailure(anyLong());
    }

    @Test
    @DisplayName("504 응답은 재시도 대상이다")
    void gatewayTimeoutIsRetryable() {
        // 테스트 목적:
        // AI Timeout을 의미하는 504 응답은
        // 재시도 가능한 실패로 분류되는지 검증한다.

        // given
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestMetric(payload)).thenThrow(HttpServerErrorException.create(
                HttpStatus.GATEWAY_TIMEOUT, "504", HttpHeaders.EMPTY, new byte[0], null));

        // when
        scheduler.processAcceptedMetrics();

        // then
        verify(processingService).handleRetryableFailure(900L);
        verify(processingService, never()).handlePermanentFailure(anyLong());
    }

    @Test
    @DisplayName("통신 오류는 재시도 대상이다")
    void connectionErrorIsRetryable() {
        // 테스트 목적:
        // AI 서버 연결 실패나 timeout 같은 통신 오류는
        // 일시 장애로 보고 재시도 처리하는지 검증한다.

        // given
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestMetric(payload)).thenThrow(new ResourceAccessException("timeout"));

        // when
        scheduler.processAcceptedMetrics();

        // then
        verify(processingService).handleRetryableFailure(900L);
        verify(processingService, never()).handlePermanentFailure(anyLong());
    }

    private AiRequest request(long id) {
        return withId(AiRequest.create(
                team, member, MetricPolicy.idempotencyKey(502L), AiRequestType.METRIC), id);
    }
}
