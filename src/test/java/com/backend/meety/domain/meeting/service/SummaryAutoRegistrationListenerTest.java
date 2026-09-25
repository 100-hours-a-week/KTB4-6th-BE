package com.backend.meety.domain.meeting.service;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.meeting.exception.SummaryErrorCode;
import com.backend.meety.domain.meeting.exception.SummaryException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

class SummaryAutoRegistrationListenerTest {

    private final MeetingSummaryService service = mock(MeetingSummaryService.class);
    private final SummaryAutoRegistrationListener listener = new SummaryAutoRegistrationListener(service);

    @Test
    @DisplayName("이벤트를 받으면 첫 요약 등록에 위임한다")
    void delegatesToService() {
        listener.registerFirstSummary(new MeetingTranscriptFinalizedEvent(100L, 700L));

        verify(service).registerFirstSummary(100L, 700L);
    }

    @Test
    @DisplayName("다른 경로가 먼저 등록한 경우의 예외는 삼킨다")
    void swallowsDuplicateRegistration() {
        doThrow(new SummaryException(SummaryErrorCode.SUMMARY_ALREADY_PROCESSING))
                .when(service).registerFirstSummary(100L, 700L);

        listener.registerFirstSummary(new MeetingTranscriptFinalizedEvent(100L, 700L));
    }

    @Test
    @DisplayName("UNIQUE 충돌 예외도 삼킨다")
    void swallowsUniqueViolation() {
        doThrow(new DataIntegrityViolationException("duplicate"))
                .when(service).registerFirstSummary(100L, 700L);

        listener.registerFirstSummary(new MeetingTranscriptFinalizedEvent(100L, 700L));
    }
}
