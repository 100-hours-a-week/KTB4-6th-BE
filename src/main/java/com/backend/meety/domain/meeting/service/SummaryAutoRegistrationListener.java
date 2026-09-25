package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.meeting.exception.SummaryException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class SummaryAutoRegistrationListener {

    private final MeetingSummaryService meetingSummaryService;

    @EventListener
    public void registerFirstSummary(MeetingTranscriptFinalizedEvent event) {
        try {
            meetingSummaryService.registerFirstSummary(event.meetingId(), event.recordingSessionId());
        } catch (DataIntegrityViolationException | SummaryException e) {
            log.debug("다른 경로에서 첫 요약이 먼저 등록되었습니다. meetingId={}", event.meetingId());
        }
    }
}
