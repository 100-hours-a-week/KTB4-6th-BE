package com.backend.meety.domain.recording.realtime;

import java.nio.ByteBuffer;

public record AudioChunkFrame(
        long sequence,
        byte[] audio
) {

    public static AudioChunkFrame create(ByteBuffer payload) {
        long sequence = payload.getLong();
        byte[] audio = new byte[payload.remaining()];
        payload.get(audio);
        return new AudioChunkFrame(sequence, audio);
    }
}
