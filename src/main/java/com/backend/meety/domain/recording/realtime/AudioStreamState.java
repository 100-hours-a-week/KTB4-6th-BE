package com.backend.meety.domain.recording.realtime;

public class AudioStreamState {

    private long lastProcessedSequence;
    private int processedSinceAck;

    public boolean isProcessed(long sequence) {
        return sequence <= lastProcessedSequence;
    }

    public boolean isGap(long sequence) {
        return sequence > lastProcessedSequence + 1;
    }

    public boolean markProcessed(long sequence) {
        lastProcessedSequence = sequence;
        processedSinceAck++;
        if (processedSinceAck < AudioChunkPolicy.ACK_INTERVAL_CHUNKS) {
            return false;
        }
        processedSinceAck = 0;
        return true;
    }

    public long lastProcessedSequence() {
        return lastProcessedSequence;
    }
}
