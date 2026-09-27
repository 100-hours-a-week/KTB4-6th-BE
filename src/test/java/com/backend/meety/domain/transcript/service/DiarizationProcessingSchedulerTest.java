package com.backend.meety.domain.transcript.service;

import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.DiarizationAiClient;
import com.backend.meety.domain.ai.client.DiarizationAiRequest;
import com.backend.meety.domain.ai.client.DiarizationAiResponse;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.DiarizationPolicy;
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

class DiarizationProcessingSchedulerTest {

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final DiarizationService service = mock(DiarizationService.class);
    private final DiarizationAiClient aiClient = mock(DiarizationAiClient.class);
    private final DiarizationProcessingScheduler scheduler = new DiarizationProcessingScheduler(
            aiRequests, service, aiClient);

    private final DiarizationAiRequest payload = new DiarizationAiRequest(
            "900", 100L, "https://bucket/meetings/100.mp4", List.of());
    private final DiarizationAiResponse response = new DiarizationAiResponse(100L, List.of());

    private Team team;
    private TeamMember member;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
        when(aiRequests.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.DIARIZATION, AiRequestStatus.ACCEPTED,
                Limit.of(DiarizationPolicy.SCHEDULER_BATCH_SIZE)))
                .thenReturn(List.of(request(900L)));
    }

    private AiRequest request(long id) {
        return withId(AiRequest.create(team, member, "DIARIZATION:MEETING:100", AiRequestType.DIARIZATION), id);
    }

    @Test
    @DisplayName("성공하면 결과를 반영한다")
    void processesAcceptedRequest() {
        when(service.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestDiarization(payload)).thenReturn(response);

        scheduler.processAcceptedDiarizations();

        verify(service).completeProcessing(900L, response);
    }

    @Test
    @DisplayName("오디오 대기 등으로 시작할 수 없으면 AI를 호출하지 않는다")
    void skipsWhenNotStartable() {
        when(service.startProcessing(900L)).thenReturn(Optional.empty());

        scheduler.processAcceptedDiarizations();

        verify(aiClient, never()).requestDiarization(any());
        verify(service, never()).completeProcessing(anyLong(), any());
    }

    @Test
    @DisplayName("422는 재시도하지 않고 최종 실패한다")
    void unprocessableEntityFailsPermanently() {
        when(service.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestDiarization(payload)).thenThrow(HttpClientErrorException.create(
                HttpStatus.UNPROCESSABLE_ENTITY, "422", HttpHeaders.EMPTY, new byte[0], null));

        scheduler.processAcceptedDiarizations();

        verify(service).handlePermanentFailure(900L);
        verify(service, never()).handleRetryableFailure(anyLong());
    }

    @Test
    @DisplayName("502·504는 재시도한다")
    void serverErrorIsRetryable() {
        when(service.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestDiarization(payload)).thenThrow(HttpServerErrorException.create(
                HttpStatus.BAD_GATEWAY, "502", HttpHeaders.EMPTY, new byte[0], null));

        scheduler.processAcceptedDiarizations();

        verify(service).handleRetryableFailure(900L);
    }

    @Test
    @DisplayName("통신 오류는 재시도한다")
    void ioErrorIsRetryable() {
        when(service.startProcessing(900L)).thenReturn(Optional.of(payload));
        when(aiClient.requestDiarization(payload)).thenThrow(new ResourceAccessException("timeout"));

        scheduler.processAcceptedDiarizations();

        verify(service).handleRetryableFailure(900L);
    }
}
