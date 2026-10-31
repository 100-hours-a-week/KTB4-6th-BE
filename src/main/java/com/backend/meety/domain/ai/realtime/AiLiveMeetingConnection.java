package com.backend.meety.domain.ai.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@Slf4j
public class AiLiveMeetingConnection {

    private final Object sendLock = new Object();
    // Next sequence to reserve for an outgoing audio attempt; all access is under sendLock.
    private long nextAudioSequence;
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    private final Long recordingSessionId;
    private final Long meetingId;
    private final String sessionStartRequestId;
    private final Long startSequence;
    private final Long transcriptSequenceStart;
    private volatile boolean recoveryFenced;
    private volatile boolean stopRequested;
    private volatile boolean replayEnabled = true;
    private volatile boolean strictRecoveryReplay;
    private long lastAcceptedTranscriptSequence;
    private volatile WebSocketSession webSocketSession;
    private volatile AiLiveMeetingConnectionState state;
    private volatile String sessionPauseRequestId;
    private volatile String sessionResumeRequestId;
    private volatile String sessionStopRequestId;
    private long controlTransitionStartedAtNanos;
    private boolean controlAckTimeoutReported;
    private volatile ScheduledFuture<?> stopTimeoutFuture;
    private volatile String owner;
    private final AtomicBoolean transcriptFinalizationStarted = new AtomicBoolean();
    private final NavigableMap<Long, Long> pendingAudio = new TreeMap<>();
    private long lastSentSequence = -1;
    private long lastAckedSequence = -1;
    private Instant lastAckReceivedAt;
    private Instant lastAckProgressAt;
    private boolean ackTimeoutReported;
    private long requiredFeSequence = -1;
    // Heartbeat state is independent of Audio ACK and protected by the existing sendLock.
    private String lastPingRequestId;
    private Instant lastPingSentAt;
    private Instant lastPongReceivedAt;
    private int consecutiveHeartbeatFailures;
    private boolean heartbeatTimeoutReported;
    private boolean heartbeatStopped;

    public record HeartbeatSnapshot(String lastPingRequestId, Instant lastPingSentAt,
                                    Instant lastPongReceivedAt, int consecutiveHeartbeatFailures,
                                    boolean timeoutReported) { }

    public record HeartbeatTimeout(String requestId, int consecutiveFailures,
                                   Instant detectedAt, boolean thresholdReached) { }

    public record HeartbeatTick(boolean pingSent, HeartbeatTimeout timeout, Exception sendFailure) { }

    public enum PongResult { ACCEPTED, STALE, EXPIRED, CLOSED }

    public enum AckResult {
        ADVANCED, DUPLICATE, STALE, INVALID, CLOSED
    }

    public record AckSnapshot(long nextAudioSequence, long lastSentSequence, long lastAckedSequence,
                              Instant lastAckReceivedAt, Instant lastAckProgressAt) {
        public long lag() {
            return Math.max(0, lastSentSequence - lastAckedSequence);
        }
    }

    public AiLiveMeetingConnection(Long recordingSessionId, Long meetingId, String sessionStartRequestId) {
        this(recordingSessionId, meetingId, sessionStartRequestId, null, null);
    }

    public AiLiveMeetingConnection(Long recordingSessionId, Long meetingId, String sessionStartRequestId,
                                   Long startSequence, Long transcriptSequenceStart) {
        this.recordingSessionId = recordingSessionId;
        this.meetingId = meetingId;
        this.sessionStartRequestId = sessionStartRequestId;
        this.startSequence = startSequence;
        this.transcriptSequenceStart = transcriptSequenceStart;
        lastAcceptedTranscriptSequence = transcriptSequenceStart == null ? 0 : transcriptSequenceStart - 1;
        if (startSequence != null) {
            nextAudioSequence = startSequence;
            lastSentSequence = startSequence - 1;
            lastAckedSequence = startSequence - 1;
            replayEnabled = false;
            strictRecoveryReplay = true;
        }
        this.state = AiLiveMeetingConnectionState.CONNECTING;
    }

    public Long recordingSessionId() {
        return recordingSessionId;
    }

    public Long meetingId() {
        return meetingId;
    }

    public String sessionStartRequestId() {
        return sessionStartRequestId;
    }

    public Long startSequence() { return startSequence; }
    public Long transcriptSequenceStart() { return transcriptSequenceStart; }
    public boolean isRecoveryConnection() { return startSequence != null; }
    public boolean isRecoveryFenced() { return recoveryFenced; }
    public boolean isStopRequested() { return stopRequested; }
    public void requestStop() { stopRequested = true; }
    public void fenceForRecovery() { recoveryFenced = true; }
    public void enableReplay() { replayEnabled = true; }
    public void finishRecoveryReplay() { strictRecoveryReplay = false; }

    public boolean attachIfActive(WebSocketSession session, BooleanSupplier current) {
        synchronized (sendLock) {
            if (recoveryFenced || stopRequested || isClosed() || !current.getAsBoolean()) { return false; }
            webSocketSession = session;
            return true;
        }
    }

    public boolean sendStart(TextMessage message) throws IOException {
        synchronized (sendLock) {
            if (recoveryFenced || stopRequested || state() != AiLiveMeetingConnectionState.CONNECTING) { return false; }
            markStartSent();
            webSocketSession.sendMessage(message);
            return true;
        }
    }

    public boolean enqueueTranscriptIfActive(long sequence, BooleanSupplier enqueue) {
        synchronized (sendLock) {
            if (recoveryFenced || isClosed() || state() == AiLiveMeetingConnectionState.ENDED
                    || !enqueue.getAsBoolean()) { return false; }
            lastAcceptedTranscriptSequence = Math.max(lastAcceptedTranscriptSequence, sequence);
            return true;
        }
    }

    public boolean enqueueOrderedIfActive(BooleanSupplier enqueue) {
        synchronized (sendLock) {
            if (recoveryFenced || isClosed() || state() == AiLiveMeetingConnectionState.ENDED
                    || !enqueue.getAsBoolean()) { return false; }
            return true;
        }
    }

    public long lastAcceptedTranscriptSequence() {
        synchronized (sendLock) { return lastAcceptedTranscriptSequence; }
    }

    public WebSocketSession webSocketSession() {
        return webSocketSession;
    }

    public synchronized AiLiveMeetingConnectionState state() {
        return state;
    }

    public String owner() {
        return owner;
    }

    public void assignOwner(String owner) {
        synchronized (sendLock) {
            this.owner = owner;
        }
    }

    public boolean beginTranscriptFinalization() {
        return transcriptFinalizationStarted.compareAndSet(false, true);
    }

    public void attach(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }

    /**
     * audio.meta와 바이너리 프레임을 한 쌍으로 전송한다. READY가 아니면 전송하지 않고 false를 반환한다.
     */
    public boolean forwardAudio(ObjectMapper objectMapper, byte[] audio,
                                long feSequence, Clock clock, int maxPendingChunks) throws IOException {
        synchronized (sendLock) {
            if (recoveryFenced || !replayEnabled || state() != AiLiveMeetingConnectionState.READY) {
                return false;
            }
            if (strictRecoveryReplay && feSequence != nextAudioSequence) { return false; }
            WebSocketSession session = webSocketSession;
            if (session == null || !session.isOpen()) {
                return false;
            }
            if (requiredFeSequence >= 0 && feSequence != requiredFeSequence) {
                return false;
            }
            if (pendingAudio.size() >= maxPendingChunks) {
                requiredFeSequence = feSequence;
                return false;
            }
            // Do not cumulatively acknowledge past a chunk rejected by our own pending limit/write failure.
            requiredFeSequence = feSequence;
            long sequence = nextAudioSequence++;
            AiAudioMetaMessage meta = AiAudioMetaMessage.of(sequence);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(meta)));
            session.sendMessage(new BinaryMessage(audio));
            Instant now = clock.instant();
            if (lastSentSequence == lastAckedSequence) {
                lastAckProgressAt = now;
            }
            lastSentSequence = sequence;
            pendingAudio.put(sequence, feSequence);
            requiredFeSequence = -1;
            return true;
        }
    }

    /** Caller also holds the FE stream-state lock: stream state -> sendLock is the global lock order. */
    public AckResult acknowledge(long sequence, Instant now, BiPredicate<Long, Integer> checkpoint) {
        long lockRequestedAt = System.nanoTime();
        synchronized (sendLock) {
            long lockAcquiredAt = System.nanoTime();
            if (recoveryFenced || state() == AiLiveMeetingConnectionState.CLOSED || state() == AiLiveMeetingConnectionState.STOP_SENT
                    || state() == AiLiveMeetingConnectionState.ENDED) {
                return AckResult.CLOSED;
            }
            if (sequence < 0 || sequence > lastSentSequence) {
                return AckResult.INVALID;
            }
            if (sequence < lastAckedSequence) {
                return AckResult.STALE;
            }
            boolean duplicate = sequence == lastAckedSequence;
            Long feSequence = pendingAudio.get(sequence);
            if (!duplicate && feSequence == null) {
                return AckResult.INVALID;
            }
            if (!duplicate) {
                lastAckedSequence = sequence;
                lastAckReceivedAt = now;
                lastAckProgressAt = lastSentSequence > lastAckedSequence ? now : null;
                ackTimeoutReported = false;
            }
            if (feSequence != null) {
                NavigableMap<Long, Long> confirmed = pendingAudio.headMap(sequence, true);
                long checkpointStartedAt = System.nanoTime();
                if (checkpoint.test(feSequence, confirmed.size())) {
                    confirmed.clear();
                }
                long checkpointElapsed = System.nanoTime() - checkpointStartedAt;
                if (checkpointElapsed >= TimeUnit.MILLISECONDS.toNanos(100)) {
                    log.warn("AI ACK checkpoint가 sendLock을 점유했습니다. recordingSessionId={}, sequence={}, lockWaitNanos={}, checkpointNanos={}",
                            recordingSessionId, sequence, lockAcquiredAt - lockRequestedAt, checkpointElapsed);
                }
            }
            return duplicate ? AckResult.DUPLICATE : AckResult.ADVANCED;
        }
    }

    public AckSnapshot ackSnapshot() {
        synchronized (sendLock) {
            return new AckSnapshot(nextAudioSequence, lastSentSequence, lastAckedSequence,
                    lastAckReceivedAt, lastAckProgressAt);
        }
    }

    public int pendingAudioCount() {
        synchronized (sendLock) {
            return pendingAudio.size();
        }
    }

    public HeartbeatSnapshot heartbeatSnapshot() {
        synchronized (sendLock) {
            return new HeartbeatSnapshot(lastPingRequestId, lastPingSentAt, lastPongReceivedAt,
                    consecutiveHeartbeatFailures, heartbeatTimeoutReported);
        }
    }

    public HeartbeatTick heartbeatTick(ObjectMapper mapper, AiHeartbeatProperties properties,
                                       Clock clock, Supplier<String> requestIds, BooleanSupplier isCurrent) {
        synchronized (sendLock) {
            if (!isCurrent.getAsBoolean() || !acceptsHeartbeatPong()) {
                return new HeartbeatTick(false, null, null);
            }
            Instant now = clock.instant();
            HeartbeatTimeout timeout = null;
            if (lastPingRequestId != null) {
                if (now.isBefore(lastPingSentAt.plus(properties.pongTimeout()))) {
                    return new HeartbeatTick(false, null, null);
                }
                if (consecutiveHeartbeatFailures < Integer.MAX_VALUE) {
                    consecutiveHeartbeatFailures++;
                }
                boolean reached = !heartbeatTimeoutReported
                        && consecutiveHeartbeatFailures >= properties.failureThreshold();
                heartbeatTimeoutReported |= reached;
                timeout = new HeartbeatTimeout(lastPingRequestId, consecutiveHeartbeatFailures, now, reached);
                lastPingRequestId = null;
            }
            if ((state() != AiLiveMeetingConnectionState.READY && state() != AiLiveMeetingConnectionState.PAUSED)
                    || (lastPingSentAt != null && now.isBefore(lastPingSentAt.plus(properties.interval())))) {
                return new HeartbeatTick(false, timeout, null);
            }
            WebSocketSession session = webSocketSession;
            if (session == null || !session.isOpen()) {
                return new HeartbeatTick(false, timeout, null);
            }
            try {
                String requestId = requestIds.get();
                session.sendMessage(new TextMessage(mapper.writeValueAsString(
                        AiHeartbeatPingMessage.of(recordingSessionId, requestId))));
                lastPingRequestId = requestId;
                lastPingSentAt = clock.instant();
                return new HeartbeatTick(true, timeout, null);
            } catch (Exception e) {
                return new HeartbeatTick(false, timeout, e);
            }
        }
    }

    public PongResult receiveHeartbeatPong(String requestId, Clock clock, Duration pongTimeout,
                                           BooleanSupplier isCurrent) {
        synchronized (sendLock) {
            if (!isCurrent.getAsBoolean() || !acceptsHeartbeatPong()) {
                return PongResult.CLOSED;
            }
            if (lastPingRequestId == null || !lastPingRequestId.equals(requestId)) {
                return PongResult.STALE;
            }
            Instant now = clock.instant();
            if (!now.isBefore(lastPingSentAt.plus(pongTimeout))) {
                return PongResult.EXPIRED;
            }
            lastPongReceivedAt = now;
            lastPingRequestId = null;
            consecutiveHeartbeatFailures = 0;
            heartbeatTimeoutReported = false;
            return PongResult.ACCEPTED;
        }
    }

    private boolean acceptsHeartbeatPong() {
        if (heartbeatStopped || recoveryFenced) {
            return false;
        }
        return switch (state()) {
            case READY, PAUSED, PAUSE_SENT, RESUME_SENT -> true;
            default -> false;
        };
    }

    public void detectAckTimeout(Instant now, Duration timeout, Consumer<AckSnapshot> onTimeout) {
        synchronized (sendLock) {
            if (recoveryFenced || state() == AiLiveMeetingConnectionState.CLOSED
                    || lastSentSequence <= lastAckedSequence || ackTimeoutReported) {
                return;
            }
            if (lastAckProgressAt != null && !now.isBefore(lastAckProgressAt.plus(timeout))) {
                ackTimeoutReported = true;
                onTimeout.accept(ackSnapshot());
            }
        }
    }

    public boolean sendPause(ObjectMapper objectMapper, String requestId) throws IOException {
        synchronized (sendLock) {
            if (state() != AiLiveMeetingConnectionState.READY) {
                return false;
            }
            WebSocketSession session = webSocketSession;
            if (session == null || !session.isOpen()) {
                return false;
            }
            this.sessionPauseRequestId = requestId;
            this.state = AiLiveMeetingConnectionState.PAUSE_SENT;
            this.controlTransitionStartedAtNanos = System.nanoTime();
            this.controlAckTimeoutReported = false;
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(AiSessionPauseMessage.of(this))));
            return true;
        }
    }

    public boolean sendResume(ObjectMapper objectMapper, String requestId) throws IOException {
        synchronized (sendLock) {
            if (state() != AiLiveMeetingConnectionState.PAUSED) {
                return false;
            }
            WebSocketSession session = webSocketSession;
            if (session == null || !session.isOpen()) {
                return false;
            }
            this.sessionResumeRequestId = requestId;
            this.state = AiLiveMeetingConnectionState.RESUME_SENT;
            this.controlTransitionStartedAtNanos = System.nanoTime();
            this.controlAckTimeoutReported = false;
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(AiSessionResumeMessage.of(this))));
            return true;
        }
    }

    public boolean sendStop(ObjectMapper objectMapper, String requestId) throws IOException {
        return sendStop(objectMapper, requestId, null);
    }

    public boolean sendStop(ObjectMapper objectMapper, String requestId, String expectedOwner) throws IOException {
        long lockRequestedAt = System.nanoTime();
        synchronized (sendLock) {
            long lockAcquiredAt = System.nanoTime();
            if (expectedOwner != null && (recoveryFenced || !expectedOwner.equals(owner))) {
                return false;
            }
            stopRequested = true;
            if (state() != AiLiveMeetingConnectionState.READY && state() != AiLiveMeetingConnectionState.PAUSED
                    && state() != AiLiveMeetingConnectionState.PAUSE_SENT
                    && state() != AiLiveMeetingConnectionState.RESUME_SENT) {
                return false;
            }
            WebSocketSession session = webSocketSession;
            if (session == null || !session.isOpen()) {
                throw new IllegalStateException("AI WebSocket session is not open");
            }
            this.sessionStopRequestId = requestId;
            this.state = AiLiveMeetingConnectionState.STOP_SENT;
            clearControlTransition();
            clearHeartbeatState();
            long writeStartedAt = System.nanoTime();
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(AiSessionStopMessage.of(this))));
            long writeElapsed = System.nanoTime() - writeStartedAt;
            log.debug("AI session.stop write 완료. recordingSessionId={}, requestId={}, sendLockWaitNanos={}, writeNanos={}",
                    recordingSessionId, requestId, lockAcquiredAt - lockRequestedAt, writeElapsed);
            clearAudioAckState();
            return true;
        }
    }

    public synchronized void markStartSent() {
        if (state != AiLiveMeetingConnectionState.CONNECTING) {
            return;
        }
        this.state = AiLiveMeetingConnectionState.START_SENT;
    }

    public synchronized void markReady() {
        if (recoveryFenced || (state != AiLiveMeetingConnectionState.CONNECTING
                && state != AiLiveMeetingConnectionState.START_SENT)) { return; }
        this.state = AiLiveMeetingConnectionState.READY;
        readyLatch.countDown();
        notifyAll();
    }

    public void markPaused() {
        synchronized (sendLock) {
            synchronized (this) {
                if (state == AiLiveMeetingConnectionState.PAUSE_SENT) {
                    this.state = AiLiveMeetingConnectionState.PAUSED;
                    clearControlTransition();
                }
            }
        }
    }

    public void markResumed() {
        synchronized (sendLock) {
            synchronized (this) {
                if (state == AiLiveMeetingConnectionState.RESUME_SENT) {
                    this.state = AiLiveMeetingConnectionState.READY;
                    clearControlTransition();
                    notifyAll();
                }
            }
        }
    }

    /** Claims a single recovery notification when a control command has not received its AI ACK in time. */
    public boolean claimControlAckTimeout(Duration timeout) {
        synchronized (sendLock) {
            AiLiveMeetingConnectionState current = state();
            if ((current != AiLiveMeetingConnectionState.PAUSE_SENT
                    && current != AiLiveMeetingConnectionState.RESUME_SENT)
                    || controlTransitionStartedAtNanos == 0 || controlAckTimeoutReported
                    || System.nanoTime() - controlTransitionStartedAtNanos < timeout.toNanos()) {
                return false;
            }
            controlAckTimeoutReported = true;
            return true;
        }
    }

    private void clearControlTransition() {
        controlTransitionStartedAtNanos = 0;
        controlAckTimeoutReported = false;
    }

    public synchronized boolean awaitState(AiLiveMeetingConnectionState expected, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (state != expected && state != AiLiveMeetingConnectionState.CLOSED) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) {
                return false;
            }
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
        }
        return state == expected;
    }

    public boolean awaitReady(Duration timeout) throws InterruptedException {
        return readyLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)
                && state() == AiLiveMeetingConnectionState.READY;
    }

    public boolean markStopSent(String requestId) {
        synchronized (sendLock) {
            synchronized (this) {
                if (state != AiLiveMeetingConnectionState.READY && state != AiLiveMeetingConnectionState.PAUSED) {
                    return false;
                }
                this.sessionStopRequestId = requestId;
                this.state = AiLiveMeetingConnectionState.STOP_SENT;
                clearControlTransition();
                clearHeartbeatState();
                return true;
            }
        }
    }

    public String sessionPauseRequestId() {
        return sessionPauseRequestId;
    }

    public String sessionResumeRequestId() {
        return sessionResumeRequestId;
    }

    public String sessionStopRequestId() {
        return sessionStopRequestId;
    }

    public void markEnded() {
        synchronized (sendLock) {
            synchronized (this) {
                if (state == AiLiveMeetingConnectionState.STOP_SENT) {
                    this.state = AiLiveMeetingConnectionState.ENDED;
                    clearHeartbeatState();
                }
            }
        }
    }

    public void setStopTimeoutFuture(ScheduledFuture<?> stopTimeoutFuture) {
        this.stopTimeoutFuture = stopTimeoutFuture;
    }

    public void cancelStopTimeout() {
        ScheduledFuture<?> future = stopTimeoutFuture;
        if (future != null) {
            future.cancel(false);
        }
    }

    public synchronized boolean isClosed() {
        return state == AiLiveMeetingConnectionState.CLOSED;
    }

    public void close() {
        synchronized (sendLock) {
            synchronized (this) {
                this.state = AiLiveMeetingConnectionState.CLOSED;
                clearAudioAckState();
                clearHeartbeatState();
                readyLatch.countDown();
                notifyAll();
            }
        }
    }

    private void clearAudioAckState() {
        pendingAudio.clear();
        lastSentSequence = -1;
        lastAckedSequence = -1;
        lastAckReceivedAt = null;
        lastAckProgressAt = null;
        ackTimeoutReported = false;
        requiredFeSequence = -1;
    }

    private void clearHeartbeatState() {
        heartbeatStopped = true;
        lastPingRequestId = null;
        lastPingSentAt = null;
        lastPongReceivedAt = null;
        consecutiveHeartbeatFailures = 0;
        heartbeatTimeoutReported = false;
    }
}
