package com.backend.meety.domain.recording.realtime;

public record RecordingStatusSseEvent(
        String type,
        Long meetingId,
        Long recordingSessionId
) {
}
