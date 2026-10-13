package com.backend.meety.domain.recording.owner;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RecordingRedisKeys {

    public static String owner(Long recordingSessionId) {
        return "recording:{" + recordingSessionId + "}:owner";
    }

    public static String sequence(Long recordingSessionId) {
        return "recording:{" + recordingSessionId + "}:sequence";
    }
}
