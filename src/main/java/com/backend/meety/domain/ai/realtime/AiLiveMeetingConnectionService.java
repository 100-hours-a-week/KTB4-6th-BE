package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.event.AiLiveMeetingReadyEvent;
import com.backend.meety.domain.ai.event.MeetingTranscriptFinalizedEvent;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.recording.owner.RecordingOwnerService;
import com.backend.meety.domain.recording.realtime.AudioWebSocketContext;
import com.backend.meety.domain.transcript.realtime.TranscriptSaveDispatcher;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiLiveMeetingConnectionService {

    private final AiLiveMeetingConnectionRegistry registry;
    private final AiLiveMeetingWebSocketClient webSocketClient;
    private final AiLiveMeetingProperties properties;
    private final AiRequestIdGenerator requestIdGenerator;
    private final AiStopTimeoutScheduler stopTimeoutScheduler;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TranscriptSaveDispatcher transcriptSaveDispatcher;
    private final RecordingOwnerService ownerService;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final AiAudioAckService audioAckService;
    private final AiHeartbeatService heartbeatService;
    private final AiRecoveryRegistry recoveries;

    void notifyTranscriptFinalized(AiLiveMeetingConnection connection) {
        transcriptSaveDispatcher.submitFinalize(connection.recordingSessionId(), () -> {
            eventPublisher.publishEvent(new MeetingTranscriptFinalizedEvent(
                    connection.meetingId(), connection.recordingSessionId()));
            releaseOwner(connection);
        });
    }

    private void releaseOwner(AiLiveMeetingConnection connection) {
        if (connection.owner() != null) {
            ownerService.release(connection.recordingSessionId(), connection.owner());
        }
    }

    void markReady(AiLiveMeetingConnection connection) {
        if (!isCurrent(connection)) { return; }
        connection.markReady();
        eventPublisher.publishEvent(new AiLiveMeetingReadyEvent(connection.recordingSessionId()));
        progress(connection);
    }

    public boolean startAndAwaitReady(AudioWebSocketContext context) {
        if (!start(context)) {
            return false;
        }
        AiLiveMeetingConnection connection = registry.find(context.recordingSessionId()).orElse(null);
        if (connection == null) {
            return false;
        }
        try {
            if (connection.awaitReady(AiLiveMeetingPolicy.READY_TIMEOUT)) {
                return true;
            }
            log.warn("AI WebSocket session.ready 대기 시간이 초과되었습니다. recordingSessionId={}",
                    context.recordingSessionId());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("AI WebSocket session.ready 대기가 중단되었습니다. recordingSessionId={}",
                    context.recordingSessionId(), e);
        }
        cleanup(connection);
        return false;
    }

    boolean start(AudioWebSocketContext context) {
        AiLiveMeetingConnection connection = new AiLiveMeetingConnection(
                context.recordingSessionId(),
                context.meetingId(),
                requestIdGenerator.sessionStartRequestId()
        );
        if (!registry.reserve(connection)) {
            log.warn("AI WebSocket connection이 이미 존재합니다. recordingSessionId={}", context.recordingSessionId());
            return false;
        }
        try {
            WebSocketSession aiSession = webSocketClient.connect(
                    new AiLiveMeetingInboundHandler(connection, this, objectMapper, transcriptSaveDispatcher),
                    aiWebSocketUri()
            );
            connection.attach(aiSession);
            sendSessionStart(connection, aiSession);
            return true;
        } catch (Exception e) {
            log.warn("AI WebSocket 연결 또는 session.start 전송에 실패했습니다. recordingSessionId={}",
                    context.recordingSessionId(), e);
            cleanup(connection);
            return false;
        }
    }

    public boolean prepareStream(AudioWebSocketContext context) {
        if (openConnection(context.recordingSessionId()).isPresent()) {
            return true;
        }
        return startAndAwaitReady(context);
    }

    public boolean hasOpenConnection(Long recordingSessionId) {
        return openConnection(recordingSessionId).isPresent();
    }

    public void closeConnection(Long recordingSessionId) {
        registry.find(recordingSessionId).ifPresent(AiLiveMeetingConnection::requestStop);
        recoveries.cancel(recordingSessionId);
        registry.find(recordingSessionId).ifPresent(this::cleanup);
    }

    public boolean awaitStreamable(Long recordingSessionId) {
        AiLiveMeetingConnection connection = openConnection(recordingSessionId).orElse(null);
        if (connection == null) {
            return false;
        }
        try {
            return connection.awaitState(AiLiveMeetingConnectionState.READY, AiLiveMeetingPolicy.READY_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Optional<AiLiveMeetingConnection> openConnection(Long recordingSessionId) {
        return registry.find(recordingSessionId).filter(found -> !found.isClosed() && !found.isRecoveryFenced());
    }

    public boolean forwardAudio(Long recordingSessionId, long feSequence, byte[] audio) {
        AiLiveMeetingConnection connection = registry.find(recordingSessionId).orElse(null);
        if (connection == null) {
            return false;
        }
        try {
            boolean forwarded = audioAckService.forwardAudio(connection, objectMapper, feSequence, audio);
            if (!forwarded && connection.state() == AiLiveMeetingConnectionState.READY
                    && (connection.webSocketSession() == null || !connection.webSocketSession().isOpen())) {
                recoverableFailure(connection, "AUDIO_FORWARD_FAILURE");
            }
            return forwarded;
        } catch (Exception e) {
            log.warn("AI WebSocket 오디오 전달에 실패했습니다. recordingSessionId={}", recordingSessionId, e);
            if (e instanceof java.io.IOException || e instanceof IllegalStateException) {
                recoverableFailure(connection, "AUDIO_FORWARD_FAILURE");
            }
            return false;
        }
    }

    void receiveAudioAck(AiLiveMeetingConnection connection, tools.jackson.databind.JsonNode message) {
        audioAckService.receive(connection, message, objectMapper);
        progress(connection);
    }

    void receiveHeartbeatPong(AiLiveMeetingConnection connection, tools.jackson.databind.JsonNode message) {
        heartbeatService.receivePong(connection, message);
    }

    public Optional<AiLiveMeetingConnectionState> findState(Long recordingSessionId) {
        return registry.find(recordingSessionId).map(AiLiveMeetingConnection::state);
    }

    public void pause(Long recordingSessionId) {
        if (recoveries.deferPause(recordingSessionId, true)) { return; }
        registry.find(recordingSessionId).ifPresentOrElse(this::pause,
                () -> log.info("일시정지할 AI WebSocket connection이 없습니다. recordingSessionId={}", recordingSessionId));
    }

    public void resume(Long recordingSessionId) {
        if (recoveries.deferPause(recordingSessionId, false)) { return; }
        registry.find(recordingSessionId).ifPresentOrElse(this::resume,
                () -> log.info("재개할 AI WebSocket connection이 없습니다. recordingSessionId={}", recordingSessionId));
    }

    public void stop(Long recordingSessionId) {
        registry.find(recordingSessionId).ifPresent(AiLiveMeetingConnection::requestStop);
        recoveries.cancel(recordingSessionId);
        registry.find(recordingSessionId).ifPresentOrElse(this::stop,
                () -> log.info("종료할 AI WebSocket connection이 없습니다. recordingSessionId={}", recordingSessionId));
    }

    private URI aiWebSocketUri() {
        return properties.websocketUrl();
    }

    private void sendSessionStart(AiLiveMeetingConnection connection, WebSocketSession aiSession) throws Exception {
        List<AiSessionParticipant> participants = meetingParticipantRepository
                .findAttendeesByMeetingId(connection.meetingId())
                .stream()
                .map(AiSessionParticipant::from)
                .toList();
        String payload = objectMapper.writeValueAsString(AiSessionStartMessage.of(connection, participants));
        connection.sendStart(new TextMessage(payload));
    }

    private void pause(AiLiveMeetingConnection connection) {
        String requestId = requestIdGenerator.sessionPauseRequestId();
        try {
            if (!connection.sendPause(objectMapper, requestId)) {
                log.info("AI WebSocket session.pause를 전송하지 않습니다. recordingSessionId={}, state={}",
                        connection.recordingSessionId(), connection.state());
            }
        } catch (Exception e) {
            log.warn("AI WebSocket session.pause 전송에 실패했습니다. recordingSessionId={}",
                    connection.recordingSessionId(), e);
        }
    }

    private void resume(AiLiveMeetingConnection connection) {
        String requestId = requestIdGenerator.sessionResumeRequestId();
        try {
            if (!connection.sendResume(objectMapper, requestId)) {
                log.info("AI WebSocket session.resume을 전송하지 않습니다. recordingSessionId={}, state={}",
                        connection.recordingSessionId(), connection.state());
            }
        } catch (Exception e) {
            log.warn("AI WebSocket session.resume 전송에 실패했습니다. recordingSessionId={}",
                    connection.recordingSessionId(), e);
        }
    }

    private void stop(AiLiveMeetingConnection connection) {
        String requestId = requestIdGenerator.sessionStopRequestId();
        try {
            if (!connection.sendStop(objectMapper, requestId)) {
                log.info("AI WebSocket session.stop을 전송하지 않습니다. recordingSessionId={}, state={}",
                        connection.recordingSessionId(), connection.state());
                if (connection.state() == AiLiveMeetingConnectionState.CONNECTING
                        || connection.state() == AiLiveMeetingConnectionState.START_SENT
                        || connection.state() == AiLiveMeetingConnectionState.CLOSED) {
                    notifyTranscriptFinalized(connection);
                    cleanup(connection);
                }
                return;
            }
            connection.setStopTimeoutFuture(stopTimeoutScheduler.schedule(
                    () -> handleStopTimeout(connection),
                    AiLiveMeetingPolicy.STOP_TIMEOUT
            ));
        } catch (Exception e) {
            log.warn("AI WebSocket session.stop 전송에 실패했습니다. recordingSessionId={}",
                    connection.recordingSessionId(), e);
            notifyTranscriptFinalized(connection);
            cleanup(connection);
        }
    }

    private void handleStopTimeout(AiLiveMeetingConnection connection) {
        if (connection.state() != AiLiveMeetingConnectionState.STOP_SENT) {
            return;
        }
        log.warn("AI WebSocket session.ended 대기 시간이 초과되었습니다. recordingSessionId={}",
                connection.recordingSessionId());
        // 이 경로의 요약은 마지막 전사가 덜 들어왔을 수 있으나 구분 없이 요약한다 (확정 정책)
        notifyTranscriptFinalized(connection);
        cleanup(connection);
    }

    void cleanup(AiLiveMeetingConnection connection) {
        recoveries.cancelConnection(connection);
        cleanup(connection, false);
    }

    public void retireForRecovery(AiLiveMeetingConnection connection) {
        connection.fenceForRecovery();
        cleanup(connection, true);
    }

    private void cleanup(AiLiveMeetingConnection connection, boolean keepOwnerPlaceholder) {
        connection.cancelStopTimeout();
        audioAckService.cleanup(connection);
        WebSocketSession aiSession = connection.webSocketSession();
        if (aiSession != null && aiSession.isOpen()) {
            try {
                aiSession.close(CloseStatus.NORMAL);
            } catch (Exception e) {
                log.warn("AI WebSocket cleanup 중 close에 실패했습니다. recordingSessionId={}",
                        connection.recordingSessionId(), e);
            }
        }
        if (!keepOwnerPlaceholder) { registry.remove(connection.recordingSessionId(), connection); }
    }

    public boolean isCurrent(AiLiveMeetingConnection connection) {
        return registry.find(connection.recordingSessionId()).orElse(null) == connection
                && !connection.isClosed() && !connection.isRecoveryFenced();
    }

    void recoverableFailure(AiLiveMeetingConnection connection, String reason) {
        if (!isCurrent(connection) || connection.isStopRequested() || connection.state() == AiLiveMeetingConnectionState.STOP_SENT
                || connection.state() == AiLiveMeetingConnectionState.ENDED) { return; }
        if (connection.owner() == null) { cleanup(connection); return; }
        eventPublisher.publishEvent(new com.backend.meety.domain.ai.event.AiRecoverableFailureEvent(
                connection.recordingSessionId(), connection.sessionStartRequestId(), reason));
    }

    private void progress(AiLiveMeetingConnection connection) {
        if (connection.isRecoveryConnection() && isCurrent(connection)) {
            eventPublisher.publishEvent(new com.backend.meety.domain.ai.event.AiRecoveryProgressEvent(
                    connection.recordingSessionId(), connection.sessionStartRequestId()));
        }
    }

    public AiLiveMeetingConnection reserveRecovery(AiLiveMeetingConnection previous, long fromSequence, long transcriptStart) {
        var next = new AiLiveMeetingConnection(previous.recordingSessionId(), previous.meetingId(),
                requestIdGenerator.sessionStartRequestId(), fromSequence, transcriptStart);
        next.assignOwner(previous.owner());
        return registry.reserve(next) ? next : null;
    }

    public void connectRecovery(AiLiveMeetingConnection connection, java.time.Duration timeout) throws Exception {
        if (!isCurrent(connection)) { return; }
        if (timeout.isNegative() || timeout.isZero()) { throw new java.util.concurrent.TimeoutException("Recovery deadline elapsed"); }
        var session = webSocketClient.connect(new AiLiveMeetingInboundHandler(connection, this, objectMapper, transcriptSaveDispatcher),
                aiWebSocketUri(), timeout);
        if (!connection.attachIfActive(session, () -> registry.find(connection.recordingSessionId()).orElse(null) == connection)) {
            session.close(CloseStatus.NORMAL);
            return;
        }
        sendSessionStart(connection, session);
    }

    public boolean restoreRecoveryPause(AiLiveMeetingConnection connection) throws java.io.IOException {
        return connection.sendPause(objectMapper, requestIdGenerator.sessionPauseRequestId());
    }

    public void detachRecovery(AiLiveMeetingConnection connection) {
        registry.detachRecovery(connection);
    }
}
