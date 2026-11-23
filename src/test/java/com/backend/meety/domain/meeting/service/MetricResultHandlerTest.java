package com.backend.meety.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.MetricAiAgenda;
import com.backend.meety.domain.ai.client.MetricAiOffTopic;
import com.backend.meety.domain.ai.client.MetricAiResponse;
import com.backend.meety.domain.ai.entity.AiRequestType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class MetricResultHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final MetricProcessingService metricProcessingService = mock(MetricProcessingService.class);
    private final MetricResultHandler handler = new MetricResultHandler(metricProcessingService, objectMapper);

    @Test
    @DisplayName("지표 작업 결과를 처리한다")
    void handlesMetric() {
        assertThat(handler.requestType()).isEqualTo(AiRequestType.METRIC);
    }

    @Test
    @DisplayName("성공 결과를 기존 Metric 응답 형식으로 바꿔 저장을 요청하고 그 수락 여부를 반환한다")
    void convertsResultToMetricResponse() {
        MetricAiResponse expected = new MetricAiResponse(
                "101", 7L, new MetricAiOffTopic(15000L), List.of(new MetricAiAgenda("배포 일정", 120000L)));
        when(metricProcessingService.completeMetric(101L, expected)).thenReturn(true);

        boolean accepted = handler.handleSucceeded(101L, objectMapper.readTree("""
                {"requestId":"101","meetingId":7,"offTopic":{"durationMs":15000},
                 "agendas":[{"title":"배포 일정","durationMs":120000}]}
                """));

        assertThat(accepted).isTrue();
        verify(metricProcessingService).completeMetric(101L, expected);
    }

    @Test
    @DisplayName("기존 Metric 응답 형식으로 해석할 수 없는 결과는 거절한다")
    void rejectsUnreadableResult() {
        boolean accepted = handler.handleSucceeded(101L, objectMapper.readTree("""
                {"requestId":"101","meetingId":"not-a-number"}
                """));

        assertThat(accepted).isFalse();
        verify(metricProcessingService, never()).completeMetric(anyLong(), any());
    }
}
