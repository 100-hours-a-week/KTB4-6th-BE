package com.backend.meety.domain.transcript.service;

import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiarizationAutoRegistrationListener {

    private final DiarizationService diarizationService;

    @EventListener
    public void registerDiarization(MeetingTranscriptFinalizedEvent event) {
        try {
            diarizationService.register(event.meetingId(), event.recordingSessionId());
        } catch (DataIntegrityViolationException e) {
            log.debug("다른 경로에서 화자 분리가 먼저 등록되었습니다. meetingId={}", event.meetingId());
        }
    }
}
