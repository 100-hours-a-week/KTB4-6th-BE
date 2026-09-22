package com.backend.meety.domain.recording.realtime;

import java.util.concurrent.atomic.AtomicLong;

public class AudioChunkStats {

    private final AtomicLong chunkCount = new AtomicLong();
    private final AtomicLong forwardedCount = new AtomicLong();
    private final AtomicLong totalBytes = new AtomicLong();

    public void record(int chunkBytes, boolean forwarded) {
        chunkCount.incrementAndGet();
        totalBytes.addAndGet(chunkBytes);
        if (forwarded) {
            forwardedCount.incrementAndGet();
        }
    }

    public long chunkCount() {
        return chunkCount.get();
    }

    public long forwardedCount() {
        return forwardedCount.get();
    }

    public long totalBytes() {
        return totalBytes.get();
    }
}
