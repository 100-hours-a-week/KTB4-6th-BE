package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.event.AiAudioAckTimeoutEvent;
import com.backend.meety.domain.ai.event.AiHeartbeatTimeoutEvent;
import com.backend.meety.domain.ai.event.AiRecoverableFailureEvent;
import com.backend.meety.domain.ai.event.AiRecoveryExhaustedEvent;
import com.backend.meety.domain.ai.event.AiRecoveryProgressEvent;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.owner.RecordingOwnerRepository;
import com.backend.meety.domain.recording.realtime.AudioSequenceRepository;
import com.backend.meety.domain.recording.realtime.AudioWebSocketRegistry;
import com.backend.meety.domain.recording.realtime.RecordingRecoveryMessage;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class AiRecoveryService {
    private final AiRecoveryRegistry recoveries;
    private final AiLiveMeetingConnectionRegistry connections;
    private final AiLiveMeetingConnectionService connectionService;
    private final RecordingOwnerRepository owners;
    private final RecordingSessionRepository recordings;
    private final TranscriptSegmentRepository transcripts;
    private final AudioSequenceRepository checkpoints;
    private final AudioWebSocketRegistry audio;
    private final AiHeartbeatService heartbeat;
    private final AiStopTimeoutScheduler timers;
    private final Executor workers;
    private final AiRecoveryProperties properties;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final ApplicationEventPublisher events;

    public AiRecoveryService(AiRecoveryRegistry recoveries, AiLiveMeetingConnectionRegistry connections,
            AiLiveMeetingConnectionService connectionService, RecordingOwnerRepository owners,
            RecordingSessionRepository recordings, TranscriptSegmentRepository transcripts,
            AudioSequenceRepository checkpoints, AudioWebSocketRegistry audio, AiHeartbeatService heartbeat,
            AiStopTimeoutScheduler timers, @Qualifier("aiRecoveryExecutor") Executor workers,
            AiRecoveryProperties properties, ObjectMapper mapper, Clock clock, MeterRegistry metrics,
            ApplicationEventPublisher events) {
        this.recoveries = recoveries;
        this.connections = connections;
        this.connectionService = connectionService;
        this.owners = owners;
        this.recordings = recordings;
        this.transcripts = transcripts;
        this.checkpoints = checkpoints;
        this.audio = audio;
        this.heartbeat = heartbeat;
        this.timers = timers;
        this.workers = workers;
        this.properties = properties;
        this.mapper = mapper;
        this.clock = clock;
        this.metrics = metrics;
        this.events = events;
        for (String name : new String[]{"started", "attempts", "success", "failure", "timeouts", "exhausted"}) {
            metrics.counter("ai.recovery." + name);
        }
    }

    @EventListener
    public void onAckTimeout(AiAudioAckTimeoutEvent event) {
        var connection = matching(event.recordingSessionId(), event.sessionStartRequestId());
        if (connection == null) { return; }
        var snapshot = connection.ackSnapshot();
        if (snapshot.lastSentSequence() <= snapshot.lastAckedSequence()
                || snapshot.lastAckedSequence() > event.lastAckedSequence()) { return; }
        enter(connection, event.reason());
    }

    @EventListener
    public void onHeartbeatTimeout(AiHeartbeatTimeoutEvent event) {
        var connection = matching(event.recordingSessionId(), event.sessionStartRequestId());
        if (connection == null || connection.heartbeatSnapshot().consecutiveHeartbeatFailures() < event.consecutiveFailures()) {
            return;
        }
        enter(connection, event.reason());
    }

    @EventListener
    public void onFailure(AiRecoverableFailureEvent event) {
        var connection = matching(event.recordingSessionId(), event.sessionStartRequestId());
        if (connection != null) { enter(connection, event.reason()); }
    }

    @EventListener
    public void onProgress(AiRecoveryProgressEvent event) {
        recoveries.find(event.recordingSessionId()).ifPresent(context -> {
            var proof = context.proof;
            var current = context.current;
            if (proof == null || !proof.connectionId.equals(event.sessionStartRequestId()) || current == null
                    || !current.sessionStartRequestId().equals(proof.connectionId) || !connectionService.isCurrent(current)) { return; }
            proof.observe(current.ackSnapshot(), current.heartbeatSnapshot());
            if (!context.advanceQueued.compareAndSet(false, true)) { return; }
            submit(context, () -> {
                context.advanceQueued.set(false);
                if (context.proof == proof) { advance(context, proof.attempt); }
            });
        });
    }

    private AiLiveMeetingConnection matching(Long id, String requestId) {
        return connections.find(id).filter(connection -> connection.sessionStartRequestId().equals(requestId)
                && connectionService.isCurrent(connection)).orElse(null);
    }

    private void enter(AiLiveMeetingConnection connection, String reason) {
        if (connection.owner() == null || connection.isStopRequested() || connection.state() == AiLiveMeetingConnectionState.STOP_SENT
                || connection.state() == AiLiveMeetingConnectionState.ENDED) { return; }
        var context = recoveries.begin(connection, reason, clock.instant());
        if (context != null) {
            if (connection.isStopRequested()) { recoveries.remove(context); return; }
            connection.fenceForRecovery();
            count("started");
            log.warn("AI recovery 시작. recordingSessionId={}, generation={}, reason={}",
                    context.recordingSessionId, context.generation, reason);
            submit(context, () -> attempt(context));
        } else {
            recoveries.find(connection.recordingSessionId()).ifPresent(existing -> {
                var proof = existing.proof;
                if (proof == null || !proof.connectionId.equals(connection.sessionStartRequestId())) { return; }
                proof.recordFailure(reason, connection.ackSnapshot(), connection.heartbeatSnapshot());
                if (!proof.failureQueued.compareAndSet(false, true)) { return; }
                submit(existing, () -> {
                    proof.failureQueued.set(false);
                    var pending = proof.failures.getAndSet(null);
                    if (pending == null) { return; }
                    if (connectionService.isCurrent(connection) && existing.proof == proof) {
                        var latestAck = connection.ackSnapshot();
                        String activeReason = pending.transport();
                        if (activeReason == null && pending.ack() != null && latestAck.lastSentSequence() > latestAck.lastAckedSequence()
                                && latestAck.lastAckedSequence() <= pending.ack()) { activeReason = "AUDIO_ACK_TIMEOUT"; }
                        if (activeReason == null && pending.heartbeat() != null && connection.heartbeatSnapshot().consecutiveHeartbeatFailures()
                                >= pending.heartbeat()) { activeReason = "AI_HEARTBEAT_TIMEOUT"; }
                        if (activeReason == null) { return; }
                        if (recoveries.isCurrent(existing)) { fail(existing, proof.attempt, activeReason); }
                        else { enter(connection, activeReason); }
                    }
                });
            });
        }
    }

    private void attempt(AiRecoveryRegistry.Context context) {
        final int number;
        final AiLiveMeetingConnection previous;
        synchronized (context) {
            if (!recoveries.isCurrent(context)) { return; }
            number = ++context.attempt;
            context.failed = false;
            context.replayRequested = false;
            context.deadline = clock.instant().plus(properties.attemptTimeout());
            previous = context.current;
        }
        count("attempts");
        log.info("AI recovery attempt. recordingSessionId={}, generation={}, attempt={}",
                context.recordingSessionId, context.generation, number);
        try {
            synchronized (context) {
                if (!active(context, number)) { return; }
                context.timeout = timers.schedule(() -> deadline(context, number), properties.attemptTimeout());
            }
            if (!owned(context)) { abandon(context); return; }
            var recording = recordings.findByIdAndDeletedAtIsNull(context.recordingSessionId).orElseThrow();
            if (recording.getStatus() != RecordingSessionStatus.RECORDING && recording.getStatus() != RecordingSessionStatus.PAUSED) {
                if (recording.getStatus() == RecordingSessionStatus.COMPLETED) { connectionService.stop(context.recordingSessionId); }
                else { connectionService.closeConnection(context.recordingSessionId); }
                return;
            }
            connectionService.retireForRecovery(previous);
            long checkpoint = checkpoints.find(context.recordingSessionId).orElse(0);
            long fromSequence = Math.addExact(checkpoint, 1);
            Long lastTranscript = transcripts.findMaxSequenceNumber(context.meetingId);
            long transcriptFloor = Math.max(lastTranscript == null ? 0 : lastTranscript,
                    previous.lastAcceptedTranscriptSequence());
            long transcriptStart = Math.addExact(transcriptFloor, 1);
            synchronized (context) {
                if (!active(context, number)) { return; }
                if (context.paused == null) { context.paused = recording.getStatus() == RecordingSessionStatus.PAUSED; }
                context.fromSequence = fromSequence;
                var next = connectionService.reserveRecovery(previous, fromSequence, transcriptStart);
                if (next == null) { throw new IllegalStateException("AI connection was replaced"); }
                context.current = next;
                context.proof = new AiRecoveryRegistry.Progress(next.sessionStartRequestId(), context.deadline, number, checkpoint);
                audio.installRecoveryState(context.recordingSessionId, next.sessionStartRequestId(), checkpoint);
            }
            AiLiveMeetingConnection next;
            synchronized (context) {
                if (!active(context, number)) { return; }
                next = context.current;
                if (number == 1) { send(context.recordingSessionId, RecordingRecoveryMessage.recovering(context.recordingSessionId)); }
            }
            connectionService.connectRecovery(next, Duration.between(clock.instant(), context.deadline));
            advance(context, number);
        } catch (Exception e) {
            log.warn("AI recovery attempt 실패. recordingSessionId={}, attempt={}", context.recordingSessionId, number, e);
            fail(context, number, "AI_RECOVERY_CONNECT_FAILURE");
        }
    }

    private void advance(AiRecoveryRegistry.Context context, int number) {
        try {
            final AiLiveMeetingConnection current;
            synchronized (context) {
                if (!active(context, number)) { return; }
                current = context.current;
            }
            if (!owned(context)) { abandon(context); return; }
            if (!connectionService.isCurrent(current)) { fail(context, number, "AI_RECOVERY_CONNECTION_LOST"); return; }
            if (current.state() != AiLiveMeetingConnectionState.READY) { return; }
            synchronized (context) {
                if (!active(context, number)) { return; }
                if (!context.replayRequested) {
                    context.replayRequested = true;
                    current.enableReplay();
                    send(context.recordingSessionId, RecordingRecoveryMessage.replay(context.recordingSessionId, context.fromSequence));
                }
            }
            heartbeat.process(current);
            if (healthy(context, number)) { complete(context, number); }
        } catch (Exception e) {
            log.warn("AI recovery 정상화 검사 실패. recordingSessionId={}", context.recordingSessionId, e);
            fail(context, number, "AI_RECOVERY_VALIDATION_FAILURE");
        }
    }

    private boolean healthy(AiRecoveryRegistry.Context context, int number) {
        synchronized (context) {
            if (!recoveries.isCurrent(context) || context.failed || context.attempt != number || !context.replayRequested
                    || !connectionService.isCurrent(context.current)
                    || context.current.isStopRequested()
                    || !context.owner.equals(context.current.owner())
                    || context.current.state() != AiLiveMeetingConnectionState.READY) { return false; }
            captureProgress(context);
            return context.proof.ack.get() >= Math.addExact(context.fromSequence, properties.successAckChunks() - 1L)
                    && context.proof.pong.get()
                    && context.current.heartbeatSnapshot().consecutiveHeartbeatFailures() == 0;
        }
    }

    private void captureProgress(AiRecoveryRegistry.Context context) {
        context.proof.observe(context.current.ackSnapshot(), context.current.heartbeatSnapshot());
    }

    private void complete(AiRecoveryRegistry.Context context, int number) throws Exception {
        if (!owned(context)) { abandon(context); return; }
        long controlVersion;
        synchronized (context) { controlVersion = context.controlVersion; }
        long checkpoint = checkpoints.find(context.recordingSessionId).orElse(0);
        var recording = recordings.findByIdAndDeletedAtIsNull(context.recordingSessionId).orElseThrow();
        if (recording.getStatus() == RecordingSessionStatus.COMPLETED || recording.getStatus() == RecordingSessionStatus.FAILED) {
            if (recording.getStatus() == RecordingSessionStatus.COMPLETED) { connectionService.stop(context.recordingSessionId); }
            else { connectionService.closeConnection(context.recordingSessionId); }
            return;
        }
        synchronized (context) {
            if (!healthy(context, number) || checkpoint < Math.addExact(context.fromSequence, properties.successAckChunks() - 1L)) { return; }
            if (context.controlVersion == controlVersion) { context.paused = recording.getStatus() == RecordingSessionStatus.PAUSED; }
            if (Boolean.TRUE.equals(context.paused) && !connectionService.restoreRecoveryPause(context.current)) {
                throw new IllegalStateException("Could not restore paused AI state");
            }
            context.current.finishRecoveryReplay();
            send(context.recordingSessionId, RecordingRecoveryMessage.completed(context.recordingSessionId));
            recoveries.remove(context);
            count("success");
            log.info("AI recovery 성공. recordingSessionId={}, generation={}, attempt={}",
                    context.recordingSessionId, context.generation, number);
        }
    }

    private void deadline(AiRecoveryRegistry.Context context, int number) {
        if (healthy(context, number)) {
            submit(context, () -> {
                try { complete(context, number); }
                catch (Exception e) { log.warn("AI recovery deadline 검증 실패", e); }
                fail(context, number, "AI_RECOVERY_ATTEMPT_TIMEOUT");
            });
        }
        else { fail(context, number, "AI_RECOVERY_ATTEMPT_TIMEOUT"); }
    }

    private void fail(AiRecoveryRegistry.Context context, int number, String reason) {
        synchronized (context) {
            if (!recoveries.isCurrent(context) || context.attempt != number || context.failed) { return; }
            context.failed = true;
            context.cancelTimer();
            if (context.current != null) { context.current.fenceForRecovery(); }
            count("failure");
            if (reason.equals("AI_RECOVERY_ATTEMPT_TIMEOUT")) { count("timeouts"); }
            log.warn("AI recovery attempt 실패. recordingSessionId={}, generation={}, attempt={}, reason={}",
                    context.recordingSessionId, context.generation, number, reason);
        }
        if (number < properties.maxAttempts()) { submit(context, () -> attempt(context)); }
        else { exhausted(context, reason); }
    }

    private boolean active(AiRecoveryRegistry.Context context, int number) {
        return recoveries.isCurrent(context) && context.attempt == number && !context.failed
                && context.current != null && !context.current.isStopRequested()
                && clock.instant().isBefore(context.deadline);
    }

    private boolean owned(AiRecoveryRegistry.Context context) {
        return owners.find(context.recordingSessionId).filter(context.owner::equals).isPresent();
    }

    private void abandon(AiRecoveryRegistry.Context context) {
        recoveries.remove(context);
        var connection = context.current;
        if (connection != null && context.owner.equals(connection.owner())) { connectionService.cleanup(connection); }
    }

    private void exhausted(AiRecoveryRegistry.Context context, String reason) {
        AiLiveMeetingConnection current;
        synchronized (context) {
            if (!recoveries.isCurrent(context)) { return; }
            recoveries.remove(context);
            current = context.current;
        }
        if (current != null) { connectionService.detachRecovery(current); }
        count("exhausted");
        log.error("AI recovery exhausted. recordingSessionId={}, generation={}, attempts={}",
                context.recordingSessionId, context.generation, context.attempt);
        if (current != null) {
            try { workers.execute(() -> connectionService.cleanup(current)); }
            catch (RuntimeException rejected) { log.error("AI recovery 물리 cleanup 지연. recordingSessionId={}", context.recordingSessionId, rejected); }
        }
        try {
            events.publishEvent(new AiRecoveryExhaustedEvent(context.recordingSessionId, context.generation,
                    context.owner, current == null ? null : current.sessionStartRequestId(), context.attempt, context.reason));
        } catch (RuntimeException e) {
            log.error("AI recovery Fatal consumer 실패. recordingSessionId={}", context.recordingSessionId, e);
        }
    }

    private void submit(AiRecoveryRegistry.Context context, Runnable task) {
        try { workers.execute(task); }
        catch (RuntimeException rejected) {
            log.error("AI recovery worker 포화/종료. recordingSessionId={}", context.recordingSessionId, rejected);
            try { timers.schedule(() -> exhausted(context, "AI_RECOVERY_EXECUTOR_REJECTED"), Duration.ZERO); }
            catch (RuntimeException shutdown) { log.error("AI recovery scheduler 종료 중 요청 거절", shutdown); }
        }
    }

    private void send(Long id, RecordingRecoveryMessage message) throws Exception {
        var session = audio.find(id).filter(org.springframework.web.socket.WebSocketSession::isOpen).orElseThrow();
        synchronized (session) { session.sendMessage(new TextMessage(mapper.writeValueAsString(message))); }
    }

    private void count(String name) { metrics.counter("ai.recovery." + name).increment(); }
}
