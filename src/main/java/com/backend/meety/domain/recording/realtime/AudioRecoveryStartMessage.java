package com.backend.meety.domain.recording.realtime;

public record AudioRecoveryStartMessage(
        String type,
        long lastProcessedSequence
) {

    private static final String TYPE = "recovery.start";

    public static AudioRecoveryStartMessage create(long lastProcessedSequence) {
        return new AudioRecoveryStartMessage(TYPE, lastProcessedSequence);
    }
}
