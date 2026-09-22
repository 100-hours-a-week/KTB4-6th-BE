package com.backend.meety.domain.recording.realtime;

import java.util.concurrent.atomic.AtomicLong;

public class AudioChunkStats {

    private final AtomicLong chunkCount = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();

    public void record(int chunkBytes) {
        chunkCount.incrementAndGet();
        totalBytes.addAndGet(chunkBytes);
    }

    public long chunkCount() {
        return chunkCount.get();
    }

    public long totalBytes() {
        return totalBytes.get();
    }
}
