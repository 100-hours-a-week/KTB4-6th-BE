package com.backend.meety.domain.ai.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRequestRecoverySchedulerTest {

    @Test
    @DisplayName("복구 Scheduler는 DB 복구 Service만 호출한다")
    void delegatesRecoveryToService() {
        // 테스트 목적:
        // stale 복구 Scheduler가 외부 AI 호출이나 도메인별 처리 없이
        // 짧은 DB 복구 Service만 실행하는지 검증한다.

        // given
        AiRequestRecoveryService service = mock(AiRequestRecoveryService.class);
        AiRequestRecoveryScheduler scheduler = new AiRequestRecoveryScheduler(service);

        // when
        scheduler.recoverStaleProcessing();

        // then
        verify(service).recoverStaleProcessing();
    }
}
