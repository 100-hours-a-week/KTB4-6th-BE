package com.backend.meety.domain.recording.event;

public record RecordingCompletedEvent(
        Long recordingSessionId
) {
}
