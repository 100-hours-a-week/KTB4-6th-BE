package com.backend.meety.domain.ai.realtime;

import java.util.concurrent.ScheduledFuture;
import org.springframework.web.socket.WebSocketSession;

public class AiLiveMeetingConnection {

    private final Long recordingSessionId;
    private final Long meetingId;
    private final AudioFormat audioFormat;
    private final String sessionStartRequestId;
    private volatile WebSocketSession webSocketSession;
    private volatile AiLiveMeetingConnectionState state;
    private volatile String sessionStopRequestId;
    private volatile ScheduledFuture<?> stopTimeoutFuture;

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

    public synchronized AiLiveMeetingConnectionState state() {
        return state;
    }

    public void attach(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }

    public synchronized void markStartSent() {
        this.state = AiLiveMeetingConnectionState.START_SENT;
    }

    public synchronized void markReady() {
        this.state = AiLiveMeetingConnectionState.READY;
    }

    public synchronized boolean markStopSent(String requestId) {
        if (state != AiLiveMeetingConnectionState.READY) {
            return false;
        }
        this.sessionStopRequestId = requestId;
        this.state = AiLiveMeetingConnectionState.STOP_SENT;
        return true;
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
    }
}
