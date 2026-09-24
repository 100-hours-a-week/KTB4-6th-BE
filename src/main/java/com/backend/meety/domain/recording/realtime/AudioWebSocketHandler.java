package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.BinaryWebSocketHandler;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioWebSocketHandler extends BinaryWebSocketHandler {

    private static final String STATS_ATTRIBUTE = "audioChunkStats";

    private final AudioWebSocketRegistry registry;
    private final AiLiveMeetingConnectionService aiConnectionService;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AudioWebSocketContext context = context(session);
        session.getAttributes().put(STATS_ATTRIBUTE, new AudioChunkStats());
        if (!registry.register(context.recordingSessionId(), session)) {
            aiConnectionService.stop(context.recordingSessionId());
            session.close(CloseStatus.POLICY_VIOLATION.withReason("audio websocket already connected"));
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        AudioWebSocketContext context = context(session);
        int chunkBytes = message.getPayloadLength();
        if (!AudioChunkPolicy.isAcceptable(chunkBytes)) {
            log.warn("허용 범위를 벗어난 오디오 청크를 수신했습니다. recordingSessionId={}, bytes={}",
                    context.recordingSessionId(), chunkBytes);
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("audio chunk size not allowed"));
            return;
        }
        byte[] audio = new byte[chunkBytes];
        message.getPayload().get(audio);
        boolean forwarded = aiConnectionService.forwardAudio(context.recordingSessionId(), audio);

        AudioChunkStats stats = stats(session);
        stats.record(chunkBytes, forwarded);
        log.debug("오디오 청크를 수신했습니다. recordingSessionId={}, bytes={}, forwarded={}, "
                        + "chunkCount={}, forwardedCount={}, totalBytes={}",
                context.recordingSessionId(), chunkBytes, forwarded,
                stats.chunkCount(), stats.forwardedCount(), stats.totalBytes());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AudioWebSocketContext context = context(session);
        AudioChunkStats stats = stats(session);
        log.info("Audio WebSocket 연결이 종료되었습니다. recordingSessionId={}, closeStatus={}, "
                        + "chunkCount={}, forwardedCount={}, totalBytes={}, aiState={}, openSessions={}",
                context.recordingSessionId(), status, stats.chunkCount(), stats.forwardedCount(),
                stats.totalBytes(), aiState(context.recordingSessionId()), registry.count());
        registry.remove(context.recordingSessionId(), session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        AudioWebSocketContext context = context(session);
        registry.remove(context.recordingSessionId(), session);
        log.warn("Audio WebSocket 오류가 발생했습니다. recordingSessionId={}, aiState={}, openSessions={}",
                context.recordingSessionId(), aiState(context.recordingSessionId()), registry.count(), exception);
    }

    private String aiState(Long recordingSessionId) {
        return aiConnectionService.findState(recordingSessionId)
                .map(Enum::name)
                .orElse("NONE");
    }

    private AudioWebSocketContext context(WebSocketSession session) {
        return (AudioWebSocketContext) session.getAttributes()
                .get(AudioWebSocketHandshakeInterceptor.CONTEXT_ATTRIBUTE);
    }

    private AudioChunkStats stats(WebSocketSession session) {
        return (AudioChunkStats) session.getAttributes()
                .computeIfAbsent(STATS_ATTRIBUTE, ignored -> new AudioChunkStats());
    }
}
