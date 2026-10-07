package com.backend.meety.domain.recording.realtime;

public record AudioAckMessage(
        String type,
        long seq
) {

    private static final String TYPE = "ack";

    public static AudioAckMessage create(long seq) {
        return new AudioAckMessage(TYPE, seq);
    }
}
