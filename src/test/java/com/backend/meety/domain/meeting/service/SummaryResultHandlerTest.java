package com.backend.meety.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.ai.entity.AiRequestType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class SummaryResultHandlerTest {

    private final SummaryProcessingService summaryProcessingService = mock(SummaryProcessingService.class);
    private final SummaryResultHandler handler = new SummaryResultHandler(summaryProcessingService);

    @Test
    @DisplayName("요약 작업 결과를 처리한다")
    void handlesSummary() {
        assertThat(handler.requestType()).isEqualTo(AiRequestType.SUMMARY);
    }

    @Test
    @DisplayName("성공 결과의 content로 요약을 완료한다")
    void completesSummaryWithContent() {
        boolean accepted = handler.handleSucceeded(101L, new ObjectMapper().readTree("{\"content\":\"요약 내용\"}"));

        assertThat(accepted).isTrue();
        verify(summaryProcessingService).completeSummary(101L, "요약 내용");
    }

    @Test
    @DisplayName("최종 실패하면 차감한 크레딧을 복구한다")
    void restoresCreditOnFinalFailure() {
        handler.handleFinalFailure(101L);

        verify(summaryProcessingService).restoreCreditIfFailed(101L);
    }
}
