package com.backend.meety.domain.ai.service;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AiRequestRecoveryScheduler {

    private final AiRequestRecoveryService recoveryService;

    @Scheduled(fixedDelayString = "${ai.request.recovery.poll-delay}")
    public void recoverStaleProcessing() {
        recoveryService.recoverStaleProcessing();
    }
}
