package com.backend.meety.domain.ai.realtime;

import java.util.Arrays;
import java.util.Optional;

public enum AudioFormat {

    WEBM_OPUS("webm_opus"),
    MP4_AAC("mp4_aac");

    private final String value;

    AudioFormat(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    public static Optional<AudioFormat> from(String value) {
        return Arrays.stream(values())
                .filter(format -> format.value.equals(value))
                .findFirst();
    }
}
