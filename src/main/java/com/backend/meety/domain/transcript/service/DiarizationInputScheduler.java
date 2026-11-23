package com.backend.meety.domain.transcript.service;

import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.transcript.DiarizationPolicy;
import com.backend.meety.global.config.SchedulingConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiarizationInputScheduler {

    private final AiRequestRepository aiRequestRepository;
    private final DiarizationService diarizationService;

    @Scheduled(fixedDelay = DiarizationPolicy.SCHEDULER_POLL_DELAY_MILLIS,
            scheduler = SchedulingConfig.AI_MESSAGING_TASK_SCHEDULER_BEAN_NAME)
    public void writeReadyMessages() {
        for (Long aiRequestId : aiRequestRepository.findIdsAwaitingPublishInput(
                AiRequestType.DIARIZATION, Limit.of(DiarizationPolicy.SCHEDULER_BATCH_SIZE))) {
            try {
                diarizationService.writeMessageWhenInputReady(aiRequestId);
            } catch (RuntimeException e) {
                log.error("화자 분리 발행 메시지 준비 중 오류가 발생했습니다. aiRequestId={}", aiRequestId, e);
            }
        }
    }
}
