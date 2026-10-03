package com.backend.meety.domain.recording.realtime;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AudioChunkPolicy {

    public static final int MAX_CHUNK_BYTES = 262_144;

    public static final int SEQUENCE_BYTES = Long.BYTES;

    public static final int MAX_FRAME_BYTES = SEQUENCE_BYTES + MAX_CHUNK_BYTES;

    public static final int ACK_INTERVAL_CHUNKS = 10;

    public static boolean isAcceptableFrame(int frameBytes) {
        return frameBytes > SEQUENCE_BYTES && frameBytes <= MAX_FRAME_BYTES;
    }
}
