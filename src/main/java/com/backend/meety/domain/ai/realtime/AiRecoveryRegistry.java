package com.backend.meety.domain.ai.realtime;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;

/** Owner-local runtime state, deliberately independent of the replaceable AI connection. */
@Component
public class AiRecoveryRegistry {
    private final ConcurrentHashMap<Long, Context> contexts = new ConcurrentHashMap<>();

    public static final class Context {
        final Long recordingSessionId;
        final Long meetingId;
        final String owner;
        final String generation = UUID.randomUUID().toString();
        final String reason;
        final Instant startedAt;
        final AiLiveMeetingConnection initialConnection;
        volatile AiLiveMeetingConnection current;
        volatile int attempt;
        boolean failed;
        volatile boolean cancelled;
        boolean replayRequested;
        final AtomicBoolean advanceQueued = new AtomicBoolean();
        Boolean paused;
        long controlVersion;
        long fromSequence;
        volatile Progress proof;
        Instant deadline;
        ScheduledFuture<?> timeout;

        Context(AiLiveMeetingConnection original, String reason, Instant now) {
            recordingSessionId = original.recordingSessionId();
            meetingId = original.meetingId();
            owner = original.owner();
            current = original;
            initialConnection = original;
            this.reason = reason;
            startedAt = now;
        }

        void cancelTimer() {
            if (timeout != null) { timeout.cancel(false); timeout = null; }
        }
    }

    static final class Progress {
        final String connectionId;
        final Instant deadline;
        final int attempt;
        final AtomicLong ack;
        final AtomicBoolean pong = new AtomicBoolean();
        final AtomicBoolean failureQueued = new AtomicBoolean();
        final AtomicReference<Failures> failures = new AtomicReference<>();

        record Failures(Long ack, Integer heartbeat, String transport) { }

        Progress(String connectionId, Instant deadline, int attempt, long checkpoint) {
            this.connectionId = connectionId;
            this.deadline = deadline;
            this.attempt = attempt;
            ack = new AtomicLong(checkpoint);
        }

        void observe(AiLiveMeetingConnection.AckSnapshot audio, AiLiveMeetingConnection.HeartbeatSnapshot heartbeat) {
            if (audio.lastAckReceivedAt() != null && !audio.lastAckReceivedAt().isAfter(deadline)) {
                ack.accumulateAndGet(audio.lastAckedSequence(), Math::max);
            }
            if (heartbeat.lastPongReceivedAt() != null && !heartbeat.lastPongReceivedAt().isAfter(deadline)) {
                pong.set(true);
            }
        }

        void recordFailure(String reason, AiLiveMeetingConnection.AckSnapshot audio,
                           AiLiveMeetingConnection.HeartbeatSnapshot heartbeat) {
            failures.updateAndGet(previous -> {
                var old = previous == null ? new Failures(null, null, null) : previous;
                if (reason.equals("AUDIO_ACK_TIMEOUT")) {
                    return new Failures(old.ack() == null ? audio.lastAckedSequence() : Math.max(old.ack(), audio.lastAckedSequence()),
                            old.heartbeat(), old.transport());
                }
                if (reason.equals("AI_HEARTBEAT_TIMEOUT")) {
                    return new Failures(old.ack(), old.heartbeat() == null ? heartbeat.consecutiveHeartbeatFailures()
                            : Math.max(old.heartbeat(), heartbeat.consecutiveHeartbeatFailures()), old.transport());
                }
                return new Failures(old.ack(), old.heartbeat(), reason);
            });
        }
    }

    public Context begin(AiLiveMeetingConnection connection, String reason, Instant now) {
        Context candidate = new Context(connection, reason, now);
        return contexts.putIfAbsent(connection.recordingSessionId(), candidate) == null ? candidate : null;
    }

    public Optional<Context> find(Long id) { return Optional.ofNullable(contexts.get(id)); }

    public boolean isCurrent(Context context) { return contexts.get(context.recordingSessionId) == context && !context.cancelled; }

    public int count() { return contexts.size(); }

    public boolean hasDifferentGeneration(Long id, String generation) {
        return find(id).filter(context -> !context.generation.equals(generation)).isPresent();
    }

    public void remove(Context context) {
        synchronized (context) {
            context.cancelled = true;
            context.cancelTimer();
            contexts.remove(context.recordingSessionId, context);
        }
    }

    public boolean deferPause(Long id, boolean paused) {
        Context context = contexts.get(id);
        if (context == null) { return false; }
        synchronized (context) {
            if (!isCurrent(context)) { return false; }
            context.paused = paused;
            context.controlVersion++;
            return true;
        }
    }

    public void cancel(Long id) { find(id).ifPresent(this::remove); }

    public void cancelConnection(AiLiveMeetingConnection connection) {
        find(connection.recordingSessionId()).ifPresent(context -> {
            synchronized (context) {
                if (context.current == connection) { remove(context); }
            }
        });
    }
}
