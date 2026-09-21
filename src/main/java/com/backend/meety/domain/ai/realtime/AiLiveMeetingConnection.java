package com.backend.meety.domain.ai.realtime;

import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

@RequiredArgsConstructor
public class AiLiveMeetingConnection {

    private static final int MAX_BINARY_BYTES = 262_144;

    private final ObjectMapper objectMapper;
    private final Object sendLock = new Object();
    private final AtomicLong sequence = new AtomicLong();

    private final Long meetingId;
    private final Long recordingSessionId;
    private final String audioFormat;
    private volatile AiSessionState state = AiSessionState.CONNECTING;
    private volatile WebSocketSession session;

    public void attach(WebSocketSession session) {
        this.session = session;
    }

    public Long meetingId() {
        return meetingId;
    }

    public Long recordingSessionId() {
        return recordingSessionId;
    }

    public String audioFormat() {
        return audioFormat;
    }

    public AiSessionState state() {
        return state;
    }

    public void sendStart() throws IOException {
        state = AiSessionState.START_SENT;
        sendControl(Map.of(
                "type", "session.start",
                "requestId", UUID.randomUUID().toString(),
                "meetingId", String.valueOf(meetingId),
                "recordingSessionId", String.valueOf(recordingSessionId),
                "payload", Map.of("audioFormat", audioFormat)
        ));
    }

    public void sendPause() throws IOException {
        state = AiSessionState.PAUSE_SENT;
        sendControl(Map.of("type", "session.pause", "requestId", UUID.randomUUID().toString()));
    }

    public void sendResume() throws IOException {
        state = AiSessionState.RESUME_SENT;
        sendControl(Map.of("type", "session.resume", "requestId", UUID.randomUUID().toString()));
    }

    public void sendStop() throws IOException {
        state = AiSessionState.STOP_SENT;
        sendControl(Map.of("type", "session.stop", "requestId", UUID.randomUUID().toString()));
    }

    public boolean forwardAudio(byte[] audio) throws IOException {
        if (audio.length < 1 || audio.length > MAX_BINARY_BYTES || state != AiSessionState.READY) {
            return false;
        }
        synchronized (sendLock) {
            long currentSequence = sequence.getAndIncrement();
            sendRaw(Map.of(
                    "type", "audio.meta",
                    "payload", Map.of("sequence", currentSequence)
            ));
            currentSession().sendMessage(new BinaryMessage(audio));
        }
        return true;
    }

    public void markReady() {
        state = AiSessionState.READY;
    }

    public void markPaused() {
        state = AiSessionState.PAUSED;
    }

    public void markEnded() {
        state = AiSessionState.ENDED;
    }

    public void close() {
        WebSocketSession current = session;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (IOException ignored) {
        }
    }

    private void sendControl(Map<String, Object> message) throws IOException {
        synchronized (sendLock) {
            sendRaw(message);
        }
    }

    private void sendRaw(Map<String, Object> message) throws IOException {
        currentSession().sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
    }

    private WebSocketSession currentSession() {
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("AI WebSocket is not open");
        }
        return session;
    }
}
