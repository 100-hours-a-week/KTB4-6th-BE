package com.backend.meety.domain.transcript.service;

import com.backend.meety.domain.ai.client.DiarizationAiClient;
import com.backend.meety.domain.ai.client.DiarizationAiRequest;
import com.backend.meety.domain.ai.client.DiarizationAiResponse;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.ai.service.AiProcessingAttempt;
import com.backend.meety.domain.transcript.DiarizationPolicy;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiarizationProcessingScheduler {

    private final AiRequestRepository aiRequestRepository;
    private final DiarizationService diarizationService;
    private final DiarizationAiClient diarizationAiClient;

    @Scheduled(fixedDelay = DiarizationPolicy.SCHEDULER_POLL_DELAY_MILLIS)
    public void processAcceptedDiarizations() {
        List<AiRequest> targets = aiRequestRepository.findByRequestTypeAndStatusOrderByIdAsc(
                AiRequestType.DIARIZATION, AiRequestStatus.ACCEPTED,
                Limit.of(DiarizationPolicy.SCHEDULER_BATCH_SIZE));
        for (AiRequest target : targets) {
            processOne(target.getId());
        }
    }

    private void processOne(Long aiRequestId) {
        AiProcessingAttempt<DiarizationAiRequest> attempt =
                diarizationService.startProcessing(aiRequestId).orElse(null);
        if (attempt == null) {
            return;
        }
        DiarizationAiResponse response;
        try {
            response = diarizationAiClient.requestDiarization(attempt.payload());
        } catch (Exception e) {
            log.warn("AI 화자 분리 호출에 실패했습니다. aiRequestId={}", aiRequestId, e);
            if (isRetryable(e)) {
                diarizationService.handleRetryableFailure(aiRequestId, attempt.processingToken());
            } else {
                diarizationService.handlePermanentFailure(aiRequestId, attempt.processingToken());
            }
            return;
        }
        diarizationService.completeProcessing(aiRequestId, attempt.processingToken(), response);
    }

    /**
     * 422(형식·디코딩 오류)는 같은 입력으로 다시 보내도 실패하므로 재시도하지 않는다. 502·504·통신 오류는 재시도한다.
     */
    private boolean isRetryable(Exception e) {
        if (e instanceof HttpClientErrorException clientError) {
            return clientError.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS;
        }
        return true;
    }
}
