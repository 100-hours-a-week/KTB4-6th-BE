package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import java.io.IOException;
import java.util.OptionalLong;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class AudioWebSocketHandler extends AbstractWebSocketHandler {

    public static final CloseStatus SUPERSEDED = new CloseStatus(4001, "superseded by new connection");
    public static final CloseStatus AI_NOT_READY = CloseStatus.SERVER_ERROR.withReason("ai not ready");
    public static final CloseStatus STREAM_RESET_FAILED = CloseStatus.SERVER_ERROR.withReason("stream reset failed");

    private static final String RECOVERY_FINISHED = "recovery.finished";
    private static final String STATS_ATTRIBUTE = "audioChunkStats";
    private static final String SENDER_ATTRIBUTE = "audioMessageSender";
    private static final String PHASE_ATTRIBUTE = "audioSocketPhase";
    private static final int SEND_TIME_LIMIT_MILLIS = 1_000;
    private static final int SEND_BUFFER_LIMIT_BYTES = 64 * 1024;

    private final AudioWebSocketRegistry registry;
    private final AiLiveMeetingConnectionService aiConnectionService;
    private final ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        AudioWebSocketContext context = context(session);
        session.getAttributes().put(STATS_ATTRIBUTE, new AudioChunkStats());
        registry.streamState(context.recordingSessionId());
        registry.replace(context.recordingSessionId(), session)
                .ifPresent(previous -> registry.closeAndRemove(context.recordingSessionId(), previous, SUPERSEDED));
        session.getAttributes().put(PHASE_ATTRIBUTE, AudioSocketPhase.RECOVERING);
        if (!aiConnectionService.awaitStreamable(context.recordingSessionId())) {
            log.warn("AI 연결이 준비되지 않아 Audio WebSocket을 닫습니다. recordingSessionId={}, aiState={}",
                    context.recordingSessionId(), aiState(context.recordingSessionId()));
            session.close(AI_NOT_READY);
            return;
        }
        sendRecoveryStart(session, context.recordingSessionId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        Long recordingSessionId = context(session).recordingSessionId();
        JsonNode root;
        try {
            root = objectMapper.readTree(message.getPayload());
        } catch (Exception e) {
            log.warn("해석할 수 없는 Audio WebSocket 텍스트 메시지입니다. recordingSessionId={}, bytes={}",
                    recordingSessionId, message.getPayloadLength());
            return;
        }
        String type = root.path("type").asText();
        if (RECOVERY_FINISHED.equals(type)) {
            finishRecovery(session, root.path("lastSequence").asLong());
            return;
        }
        log.warn("처리하지 않는 Audio WebSocket 텍스트 메시지입니다. recordingSessionId={}, type={}",
                recordingSessionId, type);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws IOException {
        AudioWebSocketContext context = context(session);
        Long recordingSessionId = context.recordingSessionId();
        int frameBytes = message.getPayloadLength();
        if (!AudioChunkPolicy.isAcceptableFrame(frameBytes)) {
            log.warn("허용 범위를 벗어난 오디오 프레임을 수신했습니다. recordingSessionId={}, bytes={}",
                    recordingSessionId, frameBytes);
            session.close(CloseStatus.NOT_ACCEPTABLE.withReason("audio frame size not allowed"));
            return;
        }
        if (phase(session) == AudioSocketPhase.RESETTING) {
            log.debug("스트림 전환 중이라 오디오 청크를 처리하지 않습니다. recordingSessionId={}", recordingSessionId);
            return;
        }
        AudioChunkFrame frame = AudioChunkFrame.create(message.getPayload());
        AudioChunkStats stats = stats(session);
        AudioStreamState state = registry.streamState(recordingSessionId);
        boolean forwarded;
        boolean ackDue = false;
        long ackSequence = 0;
        // 교체 직전의 이전 소켓과 새 소켓이 같은 녹음으로 동시에 보낼 수 있어 판정과 전달을 함께 묶는다
        synchronized (state) {
            if (state.isProcessed(frame.sequence())) {
                stats.recordDuplicate();
                return;
            }
            if (state.isGap(frame.sequence())) {
                log.warn("오디오 청크 순번이 건너뛰었습니다. recordingSessionId={}, expected={}, received={}",
                        recordingSessionId, state.lastProcessedSequence() + 1, frame.sequence());
            }
            forwarded = aiConnectionService.forwardAudio(recordingSessionId, context.streamEpoch(), frame.audio());
            if (forwarded) {
                ackDue = state.markProcessed(frame.sequence());
                ackSequence = state.lastProcessedSequence();
            }
        }
        stats.record(frame.audio().length, forwarded);
        if (ackDue) {
            sendAck(session, recordingSessionId, ackSequence);
        }
        log.debug("오디오 청크를 수신했습니다. recordingSessionId={}, sequence={}, bytes={}, forwarded={}, "
                        + "chunkCount={}, forwardedCount={}, totalBytes={}",
                recordingSessionId, frame.sequence(), frame.audio().length, forwarded,
                stats.chunkCount(), stats.forwardedCount(), stats.totalBytes());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        AudioWebSocketContext context = context(session);
        AudioChunkStats stats = stats(session);
        log.info("Audio WebSocket 연결이 종료되었습니다. recordingSessionId={}, closeStatus={}, "
                        + "chunkCount={}, forwardedCount={}, duplicateCount={}, totalBytes={}, "
                        + "lastProcessedSequence={}, aiState={}, openSessions={}",
                context.recordingSessionId(), status, stats.chunkCount(), stats.forwardedCount(),
                stats.duplicateCount(), stats.totalBytes(),
                registry.streamState(context.recordingSessionId()).lastProcessedSequence(),
                aiState(context.recordingSessionId()), registry.count());
        registry.remove(context.recordingSessionId(), session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        AudioWebSocketContext context = context(session);
        registry.remove(context.recordingSessionId(), session);
        log.warn("Audio WebSocket 오류가 발생했습니다. recordingSessionId={}, aiState={}, openSessions={}",
                context.recordingSessionId(), aiState(context.recordingSessionId()), registry.count(), exception);
    }

    private void finishRecovery(WebSocketSession session, long lastSequence) throws IOException {
        AudioWebSocketContext context = context(session);
        Long recordingSessionId = context.recordingSessionId();
        if (phase(session) != AudioSocketPhase.RECOVERING) {
            log.warn("복구 중이 아닌 소켓에서 recovery.finished를 받았습니다. recordingSessionId={}, phase={}",
                    recordingSessionId, phase(session));
            return;
        }
        AudioStreamState state = registry.streamState(recordingSessionId);
        long lastProcessedSequence;
        synchronized (state) {
            lastProcessedSequence = state.lastProcessedSequence();
        }
        if (lastProcessedSequence < lastSequence) {
            log.info("처리되지 않은 청크가 남아 복구를 다시 요청합니다. recordingSessionId={}, "
                    + "lastProcessedSequence={}, lastSequence={}", recordingSessionId, lastProcessedSequence, lastSequence);
            sendRecoveryStart(session, recordingSessionId);
            return;
        }
        session.getAttributes().put(PHASE_ATTRIBUTE, AudioSocketPhase.RESETTING);
        sendAck(session, recordingSessionId, lastProcessedSequence);
        OptionalLong streamEpoch = aiConnectionService.resetStream(recordingSessionId, context.audioFormat());
        if (streamEpoch.isEmpty()) {
            log.warn("새 스트림으로 전환하지 못해 Audio WebSocket을 닫습니다. recordingSessionId={}, aiState={}",
                    recordingSessionId, aiState(recordingSessionId));
            session.close(STREAM_RESET_FAILED);
            return;
        }
        session.getAttributes().put(AudioWebSocketHandshakeInterceptor.CONTEXT_ATTRIBUTE,
                context.withStreamEpoch(streamEpoch.getAsLong()));
        session.getAttributes().put(PHASE_ATTRIBUTE, AudioSocketPhase.STREAMING);
        send(session, recordingSessionId, AudioStreamReadyMessage.create());
    }

    private void sendRecoveryStart(WebSocketSession session, Long recordingSessionId) {
        AudioStreamState state = registry.streamState(recordingSessionId);
        long lastProcessedSequence;
        synchronized (state) {
            lastProcessedSequence = state.lastProcessedSequence();
        }
        send(session, recordingSessionId, AudioRecoveryStartMessage.create(lastProcessedSequence));
    }

    private void sendAck(WebSocketSession session, Long recordingSessionId, long sequence) {
        send(session, recordingSessionId, AudioAckMessage.create(sequence));
    }

    private void send(WebSocketSession session, Long recordingSessionId, Object message) {
        try {
            sender(session).sendMessage(new TextMessage(objectMapper.writeValueAsString(message)));
        } catch (Exception e) {
            log.warn("Audio WebSocket 메시지 전송에 실패했습니다. recordingSessionId={}, message={}",
                    recordingSessionId, message, e);
        }
    }

    private AudioSocketPhase phase(WebSocketSession session) {
        return (AudioSocketPhase) session.getAttributes()
                .getOrDefault(PHASE_ATTRIBUTE, AudioSocketPhase.STREAMING);
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

    private WebSocketSession sender(WebSocketSession session) {
        return (WebSocketSession) session.getAttributes().computeIfAbsent(SENDER_ATTRIBUTE,
                ignored -> new ConcurrentWebSocketSessionDecorator(
                        session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_LIMIT_BYTES));
    }
}
