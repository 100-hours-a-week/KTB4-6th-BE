package com.backend.meety.domain.recording.event;

public record RecordingResumedEvent(
        Long meetingId,
        Long recordingSessionId
) {
}
