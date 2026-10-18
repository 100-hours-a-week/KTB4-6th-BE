package com.backend.meety.domain.recording.owner;

public record RecordingCommandMessage(
        Long recordingSessionId,
        RecordingCommand command,
        String owner
) {
}
