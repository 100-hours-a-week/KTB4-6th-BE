package com.backend.meety.domain.ai.realtime;

import org.springframework.web.socket.WebSocketSession;

public class AiLiveMeetingConnection {

    private final Long recordingSessionId;
    private final Long meetingId;
    private final AudioFormat audioFormat;
    private final String sessionStartRequestId;
    private volatile WebSocketSession webSocketSession;
    private volatile AiLiveMeetingConnectionState state;

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

    public AiLiveMeetingConnectionState state() {
        return state;
    }

    public void attach(WebSocketSession webSocketSession) {
        this.webSocketSession = webSocketSession;
    }

    public void markStartSent() {
        this.state = AiLiveMeetingConnectionState.START_SENT;
    }

    public void markReady() {
        this.state = AiLiveMeetingConnectionState.READY;
    }

    public void close() {
        this.state = AiLiveMeetingConnectionState.CLOSED;
    }
}
