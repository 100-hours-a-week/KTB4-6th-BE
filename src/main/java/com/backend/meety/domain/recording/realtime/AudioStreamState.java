package com.backend.meety.domain.recording.realtime;

public class AudioStreamState {

    private long lastProcessedSequence;
    private int processedSinceAck;
    // FE sequence, not the independent AI audio.meta sequence.
    private long lastForwardedSequence;
    private String connectionIdentity;
    private boolean closed;

    public boolean bind(String connection) {
        if (closed) {
            return false;
        }
        if (!connection.equals(connectionIdentity)) {
            connectionIdentity = connection;
            lastForwardedSequence = lastProcessedSequence;
        }
        return true;
    }

    public boolean belongsTo(String connection) {
        return !closed && connection.equals(connectionIdentity);
    }

    public boolean isSent(long sequence) {
        return sequence <= Math.max(lastForwardedSequence, lastProcessedSequence);
    }

    public void markSent(long sequence) {
        lastForwardedSequence = Math.max(lastForwardedSequence, sequence);
    }

    public void close() {
        closed = true;
        connectionIdentity = null;
    }

    public boolean isProcessed(long sequence) {
        return sequence <= lastProcessedSequence;
    }

    public boolean isGap(long sequence) {
        return sequence > nextExpectedSequence();
    }

    public long nextExpectedSequence() {
        return Math.max(lastForwardedSequence, lastProcessedSequence) + 1;
    }

    public boolean markProcessed(long sequence) {
        return markProcessed(sequence, 1);
    }

    public boolean markProcessed(long sequence, int confirmedChunks) {
        if (sequence <= lastProcessedSequence) {
            return false;
        }
        lastProcessedSequence = sequence;
        processedSinceAck += confirmedChunks;
        if (processedSinceAck < AudioChunkPolicy.ACK_INTERVAL_CHUNKS) {
            return false;
        }
        processedSinceAck %= AudioChunkPolicy.ACK_INTERVAL_CHUNKS;
        return true;
    }

    public void restore(long sequence) {
        lastProcessedSequence = Math.max(lastProcessedSequence, sequence);
    }

    public long lastProcessedSequence() {
        return lastProcessedSequence;
    }
}
