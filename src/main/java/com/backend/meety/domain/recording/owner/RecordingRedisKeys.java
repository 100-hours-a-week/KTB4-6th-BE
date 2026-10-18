package com.backend.meety.domain.recording.owner;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RecordingRedisKeys {

    public static final String PREFIX = "recording:";
    public static final String OWNER_SUFFIX = ":owner";
    public static final String SEQUENCE_SUFFIX = ":sequence";

    public static String owner(Long recordingSessionId) {
        return hashTag(recordingSessionId) + OWNER_SUFFIX;
    }

    public static String sequence(Long recordingSessionId) {
        return hashTag(recordingSessionId) + SEQUENCE_SUFFIX;
    }

    private static String hashTag(Long recordingSessionId) {
        return PREFIX + "{" + recordingSessionId + "}";
    }
}
