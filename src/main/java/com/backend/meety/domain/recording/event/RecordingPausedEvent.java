package com.backend.meety.domain.recording.event;

public record RecordingPausedEvent(
        Long meetingId,
        Long recordingSessionId
) {
}
