package com.backend.meety.domain.ai.realtime;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.NavigableMap;
import java.util.OptionalLong;
import java.util.TreeMap;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

public class AiLiveMeetingConnection {

    private final Object sendLock = new Object();
    // Next sequence to reserve for an outgoing audio attempt; all access is under sendLock.
    private long nextAudioSequence;
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    private final Long recordingSessionId;
    private final Long meetingId;
    private volatile AudioFormat audioFormat;
    private final String sessionStartRequestId;
    private volatile WebSocketSession webSocketSession;
    private volatile AiLiveMeetingConnectionState state;
    private volatile String sessionPauseRequestId;
    private volatile String sessionResumeRequestId;
    private volatile String sessionStopRequestId;
    private volatile String decoderResetRequestId;
    private volatile CountDownLatch decoderReadyLatch = new CountDownLatch(0);
    private volatile AiLiveMeetingConnectionState stateBeforeReset;
    private long streamEpoch;
    private volatile ScheduledFuture<?> stopTimeoutFuture;
    private volatile String owner;
    private final NavigableMap<Long, Long> pendingAudio = new TreeMap<>();
    private long lastSentSequence = -1;
    private long lastAckedSequence = -1;
    private Instant lastAckReceivedAt;
    private Instant lastAckProgressAt;
    private boolean ackTimeoutReported;
    private long requiredFeSequence = -1;

    public enum AckResult {
        ADVANCED, DUPLICATE, STALE, INVALID, CLOSED
    }

    public record AckSnapshot(long nextAudioSequence, long lastSentSequence, long lastAckedSequence,
                              Instant lastAckReceivedAt, Instant lastAckProgressAt) {
        public long lag() {
            return Math.max(0, lastSentSequence - lastAckedSequence);
        }
    }

    public AiLiveMeetingConnection(Long recordingSessionId, Long meetingId,
                                   AudioFormat audioFormat, String sessionStartRequestId) {
        this.recordingSessionId = recordingSessionId;
        this.meetingId = meetingId;
        this.audioFormat = audioFormat;
        this.sessionStartRequestId = sessionStartRequestId;
        this.state = AiLiveMeetingConnectionState.CONNECTING;
    }

    public Long recordingSessionId() {
        return recordingSessionId;
    }

    public Long meetingId() {
        return meetingId;
    }

    public AudioFormat audioFormat() {
        return audioFormat;
    }

    public String sessionStartRequestId() {
        return sessionStartRequestId;
    }

    public WebSocketSession webSocketSession() {
        return webSocketSession;
    }

    public long streamEpoch() {
        synchronized (sendLock) {
            return streamEpoch;
        }
    }

    public synchronized AiLiveMeetingConnectionState state() {
        return state;
    }

    public String owner() {
        return owner;
    }

    public void assignOwner(String owner) {
        this.owner = owner;
    }

    public void attach(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }

    /**
     * audio.meta와 바이너리 프레임을 한 쌍으로 전송한다. 현재 스트림 세대가 아니거나 READY가 아니면 전송하지 않고 false를 반환한다.
     */
    public boolean forwardAudio(ObjectMapper objectMapper, long streamEpoch, byte[] audio,
                                long feSequence, Clock clock, int maxPendingChunks) throws IOException {
        synchronized (sendLock) {
            if (streamEpoch != this.streamEpoch || state() != AiLiveMeetingConnectionState.READY) {
                return false;
            }
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
        synchronized (sendLock) {
            if (state() == AiLiveMeetingConnectionState.CLOSED || state() == AiLiveMeetingConnectionState.STOP_SENT
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
                if (checkpoint.test(feSequence, confirmed.size())) {
                    confirmed.clear();
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

    public void detectAckTimeout(Instant now, Duration timeout, Consumer<AckSnapshot> onTimeout) {
        synchronized (sendLock) {
            if (state() == AiLiveMeetingConnectionState.CLOSED
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
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(AiSessionResumeMessage.of(this))));
            return true;
        }
    }

    public OptionalLong sendDecoderReset(ObjectMapper objectMapper, String requestId, AudioFormat audioFormat)
            throws IOException {
        synchronized (sendLock) {
            AiLiveMeetingConnectionState current = state();
            if (current != AiLiveMeetingConnectionState.READY && current != AiLiveMeetingConnectionState.PAUSED) {
                return OptionalLong.empty();
            }
            WebSocketSession session = webSocketSession;
            if (session == null || !session.isOpen()) {
                return OptionalLong.empty();
            }
            // forwardAudio와 같은 락에서 세대를 올려야 reset 뒤에 옛 스트림 청크가 AI로 가지 않는다
            streamEpoch++;
            this.stateBeforeReset = current;
            this.state = AiLiveMeetingConnectionState.RESET_SENT;
            this.audioFormat = audioFormat;
            this.decoderResetRequestId = requestId;
            this.decoderReadyLatch = new CountDownLatch(1);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(AiDecoderResetMessage.create(this))));
            return OptionalLong.of(streamEpoch);
        }
    }

    public boolean sendStop(ObjectMapper objectMapper, String requestId) throws IOException {
        synchronized (sendLock) {
            if (state() != AiLiveMeetingConnectionState.READY && state() != AiLiveMeetingConnectionState.PAUSED
                    && state() != AiLiveMeetingConnectionState.RESET_SENT) {
                return false;
            }
            WebSocketSession session = webSocketSession;
            if (session == null || !session.isOpen()) {
                throw new IllegalStateException("AI WebSocket session is not open");
            }
            this.sessionStopRequestId = requestId;
            this.state = AiLiveMeetingConnectionState.STOP_SENT;
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(AiSessionStopMessage.of(this))));
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
        this.state = AiLiveMeetingConnectionState.READY;
        readyLatch.countDown();
        notifyAll();
    }

    public synchronized void markPaused() {
        if (state == AiLiveMeetingConnectionState.PAUSE_SENT) {
            this.state = AiLiveMeetingConnectionState.PAUSED;
        }
    }

    public synchronized void markResumed() {
        if (state == AiLiveMeetingConnectionState.RESUME_SENT) {
            this.state = AiLiveMeetingConnectionState.READY;
            notifyAll();
        }
    }

    public synchronized void markDecoderReady() {
        if (state == AiLiveMeetingConnectionState.RESET_SENT) {
            this.state = stateBeforeReset;
            decoderReadyLatch.countDown();
            notifyAll();
        }
    }

    public boolean awaitDecoderReady(Duration timeout) throws InterruptedException {
        if (!decoderReadyLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            return false;
        }
        AiLiveMeetingConnectionState current = state();
        return current == AiLiveMeetingConnectionState.READY || current == AiLiveMeetingConnectionState.PAUSED;
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

    public synchronized boolean markStopSent(String requestId) {
        if (state != AiLiveMeetingConnectionState.READY && state != AiLiveMeetingConnectionState.PAUSED) {
            return false;
        }
        this.sessionStopRequestId = requestId;
        this.state = AiLiveMeetingConnectionState.STOP_SENT;
        return true;
    }

    public String sessionPauseRequestId() {
        return sessionPauseRequestId;
    }

    public String sessionResumeRequestId() {
        return sessionResumeRequestId;
    }

    public String decoderResetRequestId() {
        return decoderResetRequestId;
    }

    public String sessionStopRequestId() {
        return sessionStopRequestId;
    }

    public synchronized void markEnded() {
        if (state == AiLiveMeetingConnectionState.STOP_SENT) {
            this.state = AiLiveMeetingConnectionState.ENDED;
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
                readyLatch.countDown();
                decoderReadyLatch.countDown();
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
}
