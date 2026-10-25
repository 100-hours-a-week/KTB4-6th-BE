package com.backend.meety.domain.ai.realtime;

import java.util.Arrays;
import java.util.Optional;

public enum AudioFormat {

    PCM_S16LE("pcm_s16le");

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
