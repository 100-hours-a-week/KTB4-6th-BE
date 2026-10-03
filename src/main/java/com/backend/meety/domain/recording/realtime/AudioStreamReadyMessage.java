package com.backend.meety.domain.recording.realtime;

public record AudioStreamReadyMessage(
        String type
) {

    private static final String TYPE = "stream.ready";

    public static AudioStreamReadyMessage create() {
        return new AudioStreamReadyMessage(TYPE);
    }
}
