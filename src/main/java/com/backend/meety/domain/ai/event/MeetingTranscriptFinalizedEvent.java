package com.backend.meety.domain.ai.event;

public record MeetingTranscriptFinalizedEvent(
        Long meetingId,
        Long recordingSessionId
) {
}
