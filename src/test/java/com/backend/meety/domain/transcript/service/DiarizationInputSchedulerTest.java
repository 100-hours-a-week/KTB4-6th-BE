package com.backend.meety.domain.transcript.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Limit;

class DiarizationInputSchedulerTest {

    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final DiarizationService diarizationService = mock(DiarizationService.class);
    private final DiarizationInputScheduler scheduler = new DiarizationInputScheduler(aiRequests, diarizationService);

    @Test
    @DisplayName("입력 준비를 기다리는 화자 분리 요청마다 발행 메시지 준비를 시도하고 한 건 오류에도 계속 진행한다")
    void preparesEachAwaitingRequest() {
        when(aiRequests.findIdsAwaitingPublishInput(eq(AiRequestType.DIARIZATION),
                any(Limit.class))).thenReturn(List.of(1L, 2L));
        doThrow(new IllegalStateException("s3 down")).when(diarizationService).writeMessageWhenInputReady(1L);

        scheduler.writeReadyMessages();

        verify(diarizationService).writeMessageWhenInputReady(1L);
        verify(diarizationService).writeMessageWhenInputReady(2L);
    }
}
