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
            session.close(CloseStatus.POLICY_VIOLATION.withReason("audio websocket already connected"));
            return;
        }
        if (!aiConnectionService.start(context)) {
            registry.remove(context.recordingSessionId(), session);
            session.close(CloseStatus.SERVER_ERROR.withReason("ai websocket connection failed"));
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
        // TODO: 수신한 청크를 AI WebSocket으로 전달하는 작업은 별도 이슈에서 진행한다.
        AudioChunkStats stats = stats(session);
        stats.record(chunkBytes);
        log.debug("오디오 청크를 수신했습니다. recordingSessionId={}, bytes={}, chunkCount={}, totalBytes={}",
                context.recordingSessionId(), chunkBytes, stats.chunkCount(), stats.totalBytes());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AudioWebSocketContext context = context(session);
        AudioChunkStats stats = stats(session);
        log.info("Audio WebSocket 연결이 종료되었습니다. recordingSessionId={}, chunkCount={}, totalBytes={}",
                context.recordingSessionId(), stats.chunkCount(), stats.totalBytes());
        registry.remove(context.recordingSessionId(), session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        AudioWebSocketContext context = context(session);
        registry.remove(context.recordingSessionId(), session);
        log.warn("Audio WebSocket 오류가 발생했습니다. recordingSessionId={}",
                context.recordingSessionId(), exception);
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
