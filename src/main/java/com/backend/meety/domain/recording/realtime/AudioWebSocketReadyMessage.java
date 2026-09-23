package com.backend.meety.domain.recording.realtime;

public record AudioWebSocketReadyMessage(String type) {

    private static final String TYPE = "ready";

    public static AudioWebSocketReadyMessage ready() {
        return new AudioWebSocketReadyMessage(TYPE);
    }
}
