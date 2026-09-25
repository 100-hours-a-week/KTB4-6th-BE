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

import com.backend.meety.domain.ai.client.SummaryAiClient;
import com.backend.meety.domain.ai.client.SummaryAiRequest;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.meeting.SummaryPolicy;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import com.backend.meety.domain.meeting.SummaryPolicy;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpClientErrorException;

class SummaryProcessingSchedulerTest {

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final SummaryProcessingService processingService = mock(SummaryProcessingService.class);
    private final SummaryAiClient aiClient = mock(SummaryAiClient.class);
    private final SummaryProcessingScheduler scheduler = new SummaryProcessingScheduler(
            aiRequests, processingService, aiClient);

    private final SummaryAiRequest payload = new SummaryAiRequest(
            "900", 100L, "회의", "", "", "2026-09-25T14:00:00+09:00", List.of(), List.of());

    private Team team;
    private TeamMember member;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
    }

    private AiRequest request(long id) {
        return withId(AiRequest.create(team, member, "key-" + id, AiRequestType.SUMMARY), id);
    }

    @Test
    @DisplayName("성공하면 결과를 저장한다")
    void processesAcceptedRequest() {
        when(aiRequests.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.SUMMARY, AiRequestStatus.ACCEPTED, Limit.of(SummaryPolicy.SCHEDULER_BATCH_SIZE)))
                .thenReturn(List.of(request(900L)));
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestSummary(payload)).thenReturn("## 요약");

        scheduler.processAcceptedSummaries();

        verify(processingService).completeProcessing(900L, "## 요약");
        verify(processingService, never()).handleRetryableFailure(anyLong());
    }

    @Test
    @DisplayName("AI 호출이 실패해도 다음 건을 계속 처리한다")
    void continuesAfterFailure() {
        when(aiRequests.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.SUMMARY, AiRequestStatus.ACCEPTED, Limit.of(SummaryPolicy.SCHEDULER_BATCH_SIZE)))
                .thenReturn(List.of(request(900L), request(901L)));
        when(processingService.startProcessing(anyLong())).thenReturn(Optional.of(payload));
        when(aiClient.requestSummary(any()))
                .thenThrow(new RuntimeException("ai down"))
                .thenReturn("## 요약");

        scheduler.processAcceptedSummaries();

        verify(processingService).handleRetryableFailure(900L);
        verify(processingService).completeProcessing(901L, "## 요약");
    }

    @Test
    @DisplayName("4xx 응답이면 재시도 없이 즉시 실패 처리한다")
    void failsPermanentlyOnClientError() {
        when(aiRequests.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.SUMMARY, AiRequestStatus.ACCEPTED, Limit.of(SummaryPolicy.SCHEDULER_BATCH_SIZE)))
                .thenReturn(List.of(request(900L)));
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestSummary(payload))
                .thenThrow(HttpClientErrorException.create(HttpStatus.BAD_REQUEST, "Bad Request",
                        HttpHeaders.EMPTY, new byte[0], null));

        scheduler.processAcceptedSummaries();

        verify(processingService).handlePermanentFailure(900L);
        verify(processingService, never()).handleRetryableFailure(anyLong());
    }

    @Test
    @DisplayName("429 응답은 일시적 과부하이므로 재시도한다")
    void retriesOnTooManyRequests() {
        when(aiRequests.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.SUMMARY, AiRequestStatus.ACCEPTED, Limit.of(SummaryPolicy.SCHEDULER_BATCH_SIZE)))
                .thenReturn(List.of(request(900L)));
        when(processingService.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestSummary(payload))
                .thenThrow(HttpClientErrorException.create(HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests",
                        HttpHeaders.EMPTY, new byte[0], null));

        scheduler.processAcceptedSummaries();

        verify(processingService).handleRetryableFailure(900L);
        verify(processingService, never()).handlePermanentFailure(anyLong());
    }

    @Test
    @DisplayName("선점당한 요청은 AI를 호출하지 않는다")
    void skipsWhenStartProcessingRejects() {
        when(aiRequests.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.SUMMARY, AiRequestStatus.ACCEPTED, Limit.of(SummaryPolicy.SCHEDULER_BATCH_SIZE)))
                .thenReturn(List.of(request(900L)));
        when(processingService.startProcessing(900L)).thenReturn(Optional.empty());

        scheduler.processAcceptedSummaries();

        verify(aiClient, never()).requestSummary(any());
    }
}
