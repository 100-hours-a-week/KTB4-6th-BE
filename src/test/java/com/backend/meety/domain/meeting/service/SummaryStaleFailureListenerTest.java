package com.backend.meety.domain.meeting.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.event.StaleAiRequestFailedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class SummaryStaleFailureListenerTest {

    private final SummaryProcessingService summaryProcessingService = mock(SummaryProcessingService.class);
    private final SummaryStaleFailureListener listener =
            new SummaryStaleFailureListener(summaryProcessingService);

    @Test
    @DisplayName("stale Summary가 최종 실패하면 기존 크레딧 복구 정책을 실행한다")
    void restoresCreditForStaleSummaryFailure() {
        // 테스트 목적:
        // 조건부 UPDATE로 최종 실패한 요청이 Summary인 경우에도
        // 기존 수동 요약 재생성의 크레딧 복구 정책이 유지되는지 검증한다.

        // given
        StaleAiRequestFailedEvent event = new StaleAiRequestFailedEvent(90L, AiRequestType.SUMMARY);

        // when
        listener.restoreCredit(event);

        // then
        verify(summaryProcessingService).restoreCreditAfterStaleFailure(90L);
    }

    @Test
    @DisplayName("stale Diarization과 Metric 최종 실패는 Summary 크레딧 복구 대상이 아니다")
    void skipsNonSummaryFailure() {
        // 테스트 목적:
        // 크레딧 복구 후처리가 Summary 도메인 밖의 요청에는 적용되지 않는지 검증한다.

        // given
        StaleAiRequestFailedEvent diarization =
                new StaleAiRequestFailedEvent(91L, AiRequestType.DIARIZATION);
        StaleAiRequestFailedEvent metric =
                new StaleAiRequestFailedEvent(92L, AiRequestType.METRIC);

        // when
        listener.restoreCredit(diarization);
        listener.restoreCredit(metric);

        // then
        verify(summaryProcessingService, never()).restoreCreditAfterStaleFailure(91L);
        verify(summaryProcessingService, never()).restoreCreditAfterStaleFailure(92L);
    }
}
