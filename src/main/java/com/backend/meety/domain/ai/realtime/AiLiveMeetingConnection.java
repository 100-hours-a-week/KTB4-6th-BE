package com.backend.meety.domain.ai.realtime;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

public class AiLiveMeetingConnection {

    private final Object sendLock = new Object();
    private final AtomicLong audioSequence = new AtomicLong();
    private final CountDownLatch readyLatch = new CountDownLatch(1);

    private final Long recordingSessionId;
    private final Long meetingId;
    private final String sessionStartRequestId;
    private volatile WebSocketSession webSocketSession;
    private volatile AiLiveMeetingConnectionState state;
    private volatile String sessionPauseRequestId;
    private volatile String sessionResumeRequestId;
    private volatile String sessionStopRequestId;
    private volatile ScheduledFuture<?> stopTimeoutFuture;
    private volatile String owner;

    public AiLiveMeetingConnection(Long recordingSessionId, Long meetingId, String sessionStartRequestId) {
        this.recordingSessionId = recordingSessionId;
        this.meetingId = meetingId;
        this.sessionStartRequestId = sessionStartRequestId;
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
        this.owner = owner;
    }

    public void attach(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }

    /**
     * audio.meta와 바이너리 프레임을 한 쌍으로 전송한다. READY가 아니면 전송하지 않고 false를 반환한다.
     */
    public boolean forwardAudio(ObjectMapper objectMapper, byte[] audio) throws IOException {
        synchronized (sendLock) {
            if (state() != AiLiveMeetingConnectionState.READY) {
                return false;
            }
            WebSocketSession session = webSocketSession;
            if (session == null || !session.isOpen()) {
                return false;
            }
            AiAudioMetaMessage meta = AiAudioMetaMessage.of(audioSequence.getAndIncrement());
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(meta)));
            session.sendMessage(new BinaryMessage(audio));
            return true;
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

    public boolean sendStop(ObjectMapper objectMapper, String requestId) throws IOException {
        synchronized (sendLock) {
            if (state() != AiLiveMeetingConnectionState.READY && state() != AiLiveMeetingConnectionState.PAUSED) {
                return false;
            }
            WebSocketSession session = webSocketSession;
            if (session == null || !session.isOpen()) {
                throw new IllegalStateException("AI WebSocket session is not open");
            }
            this.sessionStopRequestId = requestId;
            this.state = AiLiveMeetingConnectionState.STOP_SENT;
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(AiSessionStopMessage.of(this))));
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

    public synchronized void close() {
        this.state = AiLiveMeetingConnectionState.CLOSED;
        readyLatch.countDown();
        notifyAll();
    }
}
