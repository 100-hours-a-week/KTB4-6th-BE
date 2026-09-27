package com.backend.meety.domain.transcript.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class DiarizationAutoRegistrationListenerTest {

    private final DiarizationService service = mock(DiarizationService.class);
    private final DiarizationAutoRegistrationListener listener = new DiarizationAutoRegistrationListener(service);

    @Test
    @DisplayName("이벤트를 받으면 화자 분리 등록에 위임한다")
    void delegatesToService() {
        listener.registerDiarization(new MeetingTranscriptFinalizedEvent(100L, 700L));

        verify(service).register(100L, 700L);
    }

    @Test
    @DisplayName("UNIQUE 충돌 예외는 삼킨다")
    void swallowsUniqueViolation() {
        doThrow(new DataIntegrityViolationException("duplicate")).when(service).register(100L, 700L);

        listener.registerDiarization(new MeetingTranscriptFinalizedEvent(100L, 700L));
    }
}
