package com.backend.meety.domain.recording.realtime;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AudioChunkPolicy {

    public static final int MAX_CHUNK_BYTES = 262_144;

    public static boolean isAcceptable(int chunkBytes) {
        return chunkBytes > 0 && chunkBytes <= MAX_CHUNK_BYTES;
    }
}
