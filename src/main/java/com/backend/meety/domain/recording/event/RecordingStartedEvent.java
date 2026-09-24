package com.backend.meety.domain.recording.event;

public record RecordingStartedEvent(
        Long meetingId,
        Long recordingSessionId
) {
}
