package com.backend.meety.domain.recording.event;

public record RecordingLifecycleEvent(
        RecordingLifecycleEventType type,
        Long meetingId,
        Long recordingSessionId
) {
}
