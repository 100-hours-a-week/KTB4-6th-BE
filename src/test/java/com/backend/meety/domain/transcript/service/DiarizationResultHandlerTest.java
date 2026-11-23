package com.backend.meety.domain.transcript.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.DiarizationAiResponse;
import com.backend.meety.domain.ai.client.DiarizationAiResultSegment;
import com.backend.meety.domain.ai.entity.AiRequestType;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

class DiarizationResultHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final DiarizationService diarizationService = mock(DiarizationService.class);
    private final DiarizationResultHandler handler = new DiarizationResultHandler(diarizationService, objectMapper);

    @Test
    @DisplayName("화자 분리 작업 결과를 처리한다")
    void handlesDiarization() {
        assertThat(handler.requestType()).isEqualTo(AiRequestType.DIARIZATION);
    }

    @Test
    @DisplayName("성공 결과를 기존 화자 분리 응답 형식으로 바꿔 반영을 요청하고 그 수락 여부를 반환한다")
    void convertsResultToDiarizationResponse() {
        DiarizationAiResponse expected = new DiarizationAiResponse(42L, List.of(
                new DiarizationAiResultSegment(801L, "배포 일정", 0L, 2500L, 1L)));
        when(diarizationService.completeDiarization(101L, expected)).thenReturn(true);

        boolean accepted = handler.handleSucceeded(101L, objectMapper.readTree("""
                {"meetingId":42,"segments":[{"segmentId":801,"content":"배포 일정","startedAtMs":0,
                 "endedAtMs":2500,"speakerId":1}]}
                """));

        assertThat(accepted).isTrue();
        verify(diarizationService).completeDiarization(101L, expected);
    }

    @Test
    @DisplayName("구간 목록이 없거나 해석할 수 없는 결과는 거절한다")
    void rejectsInvalidResult() {
        assertThat(handler.handleSucceeded(101L, objectMapper.readTree("{\"meetingId\":42}"))).isFalse();
        assertThat(handler.handleSucceeded(101L, objectMapper.readTree("{\"meetingId\":\"x\",\"segments\":[]}")))
                .isFalse();

        verify(diarizationService, never()).completeDiarization(anyLong(), any());
    }
}
