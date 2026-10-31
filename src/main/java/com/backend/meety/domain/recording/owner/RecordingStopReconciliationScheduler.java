package com.backend.meety.domain.recording.owner;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionState;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.realtime.AudioWebSocketCloser;
import com.backend.meety.domain.recording.repository.RecordingStatusSnapshot;
import com.backend.meety.domain.recording.service.RecordingStatusPrimaryReader;
import com.backend.meety.global.config.SchedulingConfig;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class RecordingStopReconciliationScheduler {

    private static final int STATUS_BATCH_SIZE = 100;

    private final RecordingStopReconciliationProperties properties;
    private final AiLiveMeetingConnectionRegistry connections;
    private final RecordingStatusPrimaryReader statuses;
    private final RecordingOwnerRepository owners;
    private final AiLiveMeetingConnectionService ai;
    private final AudioWebSocketCloser audio;
    private final MeterRegistry metrics;
    private final ConcurrentHashMap<AiLiveMeetingConnection, Long> mismatchStartedAt = new ConcurrentHashMap<>();
    private final io.micrometer.core.instrument.Timer dbLookupTimer;
    private final io.micrometer.core.instrument.Timer ownerLookupTimer;

    public RecordingStopReconciliationScheduler(RecordingStopReconciliationProperties properties,
            AiLiveMeetingConnectionRegistry connections, RecordingStatusPrimaryReader statuses,
            RecordingOwnerRepository owners, AiLiveMeetingConnectionService ai,
            AudioWebSocketCloser audio, MeterRegistry metrics) {
        this.properties = properties;
        this.connections = connections;
        this.statuses = statuses;
        this.owners = owners;
        this.ai = ai;
        this.audio = audio;
        this.metrics = metrics;
        this.dbLookupTimer = metrics.timer("recording.stop.reconciliation.db.lookup");
        this.ownerLookupTimer = metrics.timer("recording.stop.reconciliation.owner.lookup");
    }

    @Scheduled(fixedDelayString = "${recording.stop-reconciliation.interval}",
            scheduler = SchedulingConfig.RECORDING_STOP_RECONCILIATION_SCHEDULER_BEAN_NAME)
    public void scan() {
        if (!properties.enabled() && !properties.pauseResumeEnabled()) {
            return;
        }
        List<AiLiveMeetingConnection> candidates = connections.findAll().stream()
                .filter(this::eligible)
                .toList();
        mismatchStartedAt.keySet().removeIf(connection -> !candidates.contains(connection));
        if (candidates.isEmpty()) {
            return;
        }
        if (properties.enabled()) {
            metrics.counter("recording.stop.reconciliation.scans").increment();
        }
        for (int start = 0; start < candidates.size(); start += STATUS_BATCH_SIZE) {
            List<AiLiveMeetingConnection> batch = candidates.subList(start,
                    Math.min(start + STATUS_BATCH_SIZE, candidates.size()));
            Map<Long, RecordingStatusSnapshot> byId = readStatuses(batch);
            if (byId == null) {
                continue;
            }
            for (AiLiveMeetingConnection connection : batch) {
                try {
                    reconcile(connection, byId.get(connection.recordingSessionId()));
                } catch (RuntimeException e) {
                    metrics.counter("recording.control.reconciliation.failures").increment();
                    log.warn("녹음 제어 상태 보정에 실패했습니다. recordingSessionId={}",
                            connection.recordingSessionId(), e);
                }
            }
        }
    }

    private Map<Long, RecordingStatusSnapshot> readStatuses(List<AiLiveMeetingConnection> batch) {
        long startedAt = System.nanoTime();
        try {
            Map<Long, RecordingStatusSnapshot> byId = new HashMap<>();
            metrics.counter("recording.stop.reconciliation.db.queries").increment();
            for (RecordingStatusSnapshot status : statuses.findStatuses(
                    batch.stream().map(AiLiveMeetingConnection::recordingSessionId).toList())) {
                byId.put(status.id(), status);
            }
            dbLookupTimer.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            return byId;
        } catch (RuntimeException e) {
            dbLookupTimer.record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
            metrics.counter("recording.stop.reconciliation.db.failures").increment();
            log.warn("녹음 제어 보정용 Primary 상태 조회에 실패했습니다. candidateCount={}", batch.size(), e);
            return null;
        }
    }

    private void reconcile(AiLiveMeetingConnection connection, RecordingStatusSnapshot status) {
        if (status == null || !connection.meetingId().equals(status.meetingId()) || !ai.isCurrent(connection)) {
            return;
        }
        // STOP remains the terminal, higher-priority state transition.
        if (status.status() == RecordingSessionStatus.COMPLETED) {
            mismatchStartedAt.remove(connection);
            if (properties.enabled()) {
                reconcileStop(connection);
            }
            return;
        }
        if (!properties.pauseResumeEnabled() || !eligible(connection)
                || (status.status() != RecordingSessionStatus.PAUSED
                && status.status() != RecordingSessionStatus.RECORDING)) {
            mismatchStartedAt.remove(connection);
            return;
        }

        AiLiveMeetingConnectionState state = connection.state();
        boolean aligned = status.status() == RecordingSessionStatus.PAUSED
                ? state == AiLiveMeetingConnectionState.PAUSED
                : state == AiLiveMeetingConnectionState.READY;
        if (aligned) {
            Long startedAt = mismatchStartedAt.remove(connection);
            if (startedAt != null) {
                metrics.counter("recording.control.reconciliation.aligned").increment();
                metrics.timer("recording.control.reconciliation.recovery.duration")
                        .record(System.nanoTime() - startedAt, TimeUnit.NANOSECONDS);
                log.info("녹음 제어 상태가 최신 DB 목표와 일치합니다. recordingSessionId={}, dbStatus={}, aiStatus={}, ownerCheck=not-needed",
                        connection.recordingSessionId(), status.status(), state);
            }
            return;
        }

        boolean pause = status.status() == RecordingSessionStatus.PAUSED
                && state == AiLiveMeetingConnectionState.READY;
        boolean resume = status.status() == RecordingSessionStatus.RECORDING
                && state == AiLiveMeetingConnectionState.PAUSED;
        boolean waitingForAck = state == AiLiveMeetingConnectionState.PAUSE_SENT
                || state == AiLiveMeetingConnectionState.RESUME_SENT;
        if (!pause && !resume && !waitingForAck) {
            return;
        }
        String localOwner = connection.owner();
        Optional<String> currentOwner;
        long ownerLookupStartedAt = System.nanoTime();
        try {
            currentOwner = owners.find(connection.recordingSessionId());
        } catch (DataAccessException e) {
            ownerLookupTimer.record(System.nanoTime() - ownerLookupStartedAt, TimeUnit.NANOSECONDS);
            metrics.counter("recording.control.reconciliation.owner.unavailable").increment();
            log.warn("Redis Owner 확인 불가로 PAUSE/RESUME 보정을 보류합니다. recordingSessionId={}, dbStatus={}, aiStatus={}",
                    connection.recordingSessionId(), status.status(), state, e);
            return;
        }
        ownerLookupTimer.record(System.nanoTime() - ownerLookupStartedAt, TimeUnit.NANOSECONDS);
        if (currentOwner.isEmpty() || !currentOwner.get().equals(localOwner)) {
            metrics.counter("recording.control.reconciliation.owner.mismatch").increment();
            log.warn("현재 Owner가 아니어서 PAUSE/RESUME 보정을 보류합니다. recordingSessionId={}, dbStatus={}, aiStatus={}, localOwner={}, redisOwner={}",
                    connection.recordingSessionId(), status.status(), state, localOwner, currentOwner.orElse(null));
            return;
        }

        Long firstMismatch = mismatchStartedAt.putIfAbsent(connection, System.nanoTime());
        if (firstMismatch == null) {
            metrics.counter("recording.control.reconciliation.detected").increment();
        }
        if (waitingForAck) {
            if (connection.claimControlAckTimeout(properties.controlAckTimeout())) {
                metrics.counter("recording.control.reconciliation.ack_timeout").increment();
                log.warn("AI 제어 ACK 시간 초과로 기존 AI Recovery를 요청합니다. recordingSessionId={}, dbStatus={}, aiStatus={}, owner=verified",
                        connection.recordingSessionId(), status.status(), state);
                ai.controlAckTimedOut(connection);
            }
            return;
        }

        boolean sent = pause ? ai.reconcilePause(connection, localOwner)
                : ai.reconcileResume(connection, localOwner);
        if (sent) {
            metrics.counter(pause ? "recording.control.reconciliation.pause.sent"
                    : "recording.control.reconciliation.resume.sent").increment();
            log.warn("DB 상태와 AI 상태 불일치를 감지해 제어 명령을 보냈습니다. recordingSessionId={}, dbStatus={}, aiStatus={}, command={}, owner=verified",
                    connection.recordingSessionId(), status.status(), state, pause ? "PAUSE" : "RESUME");
        } else {
            metrics.counter("recording.control.reconciliation.command.deferred").increment();
        }
    }

    private void reconcileStop(AiLiveMeetingConnection connection) {
        if (connection.isRecoveryFenced()) {
            metrics.counter("recording.stop.reconciliation.recovery_fenced").increment();
            log.info("Recovery가 fencing한 연결이라 STOP 보정을 보류합니다. recordingSessionId={}",
                    connection.recordingSessionId());
            return;
        }
        if (connection.isStopRequested() || connection.state() == AiLiveMeetingConnectionState.STOP_SENT) {
            metrics.counter("recording.stop.reconciliation.already_requested").increment();
            return;
        }
        if (connection.owner() == null || connection.isClosed()
                || !eligible(connection) || !ai.isCurrent(connection)) {
            metrics.counter("recording.stop.reconciliation.connection_ineligible").increment();
            return;
        }
        String localOwner = connection.owner();
        if (connections.find(connection.recordingSessionId()).orElse(null) != connection) {
            metrics.counter("recording.stop.reconciliation.connection_replaced").increment();
            return;
        }
        metrics.counter("recording.stop.reconciliation.detected").increment();
        Optional<String> currentOwner;
        try {
            currentOwner = owners.find(connection.recordingSessionId());
        } catch (DataAccessException e) {
            if (isRedisUnavailable(e)) {
                reconcileWithLocalOwner(connection, localOwner, e);
            } else {
                metrics.counter("recording.stop.reconciliation.redis_lookup_failed").increment();
                log.warn("Redis Owner 오류가 연결 장애로 확인되지 않아 STOP 보정을 보류합니다. recordingSessionId={}, ownerCheck=unavailable",
                        connection.recordingSessionId(), e);
            }
            return;
        }
        if (currentOwner.isEmpty()) {
            metrics.counter("recording.stop.reconciliation.owner_missing.deferred").increment();
            log.warn("Owner Key가 없어 STOP 보정을 보류합니다. recordingSessionId={}, ownerCheck=redis_missing",
                    connection.recordingSessionId());
            return;
        }
        if (!currentOwner.get().equals(localOwner)) {
            metrics.counter("recording.stop.reconciliation.owner.mismatch").increment();
            log.warn("현재 Owner가 아니어서 STOP 보정을 보류합니다. recordingSessionId={}, ownerCheck=redis_mismatch",
                    connection.recordingSessionId());
            return;
        }
        dispatchStop(connection, localOwner, "redis_verified");
    }

    private void reconcileWithLocalOwner(AiLiveMeetingConnection connection, String localOwner,
            DataAccessException failure) {
        metrics.counter("recording.stop.reconciliation.owner.unavailable").increment();
        if (localOwner == null || !localOwner.equals(connection.owner())) {
            metrics.counter("recording.stop.reconciliation.connection_ineligible").increment();
            log.warn("Redis Owner 조회 장애 중 로컬 Owner 검증에 실패해 STOP fallback을 보류합니다. recordingSessionId={}, ownerCheck=local_invalid",
                    connection.recordingSessionId(), failure);
            return;
        }
        if (connection.isRecoveryFenced()) {
            metrics.counter("recording.stop.reconciliation.recovery_fenced").increment();
            log.info("Redis Owner 조회 장애 중 Recovery fenced 연결이라 STOP fallback을 보류합니다. recordingSessionId={}",
                    connection.recordingSessionId());
            return;
        }
        if (connection.isStopRequested() || connection.state() == AiLiveMeetingConnectionState.STOP_SENT) {
            metrics.counter("recording.stop.reconciliation.already_requested").increment();
            return;
        }
        if (connection.isClosed() || !eligible(connection) || !ai.isCurrent(connection)
                || connections.find(connection.recordingSessionId()).orElse(null) != connection) {
            metrics.counter("recording.stop.reconciliation.connection_replaced").increment();
            log.info("Redis Owner 조회 장애 중 현재 연결이 아니어서 STOP fallback을 보류합니다. recordingSessionId={}, ownerCheck=local_stale",
                    connection.recordingSessionId());
            return;
        }
        metrics.counter("recording.stop.reconciliation.local_fallback.attempted").increment();
        log.warn("Redis Owner 조회 장애로 제한적 로컬 STOP fallback을 시도합니다. recordingSessionId={}, ownerCheck=local_only",
                connection.recordingSessionId());
        dispatchStop(connection, localOwner, "local_fallback");
    }

    private void dispatchStop(AiLiveMeetingConnection connection, String localOwner, String path) {
        AiLiveMeetingConnectionService.ReconciliationStopResult result =
                ai.stopCurrentForReconciliation(connection, localOwner);
        switch (result) {
            case SENT -> {
                audio.close(connection.recordingSessionId());
                metrics.counter("recording.stop.reconciliation.started").increment();
                metrics.counter("recording.stop.reconciliation." + path + ".sent").increment();
                log.warn("DB COMPLETED를 근거로 AI STOP을 전송했습니다. recordingSessionId={}, ownerCheck={}",
                        connection.recordingSessionId(), path);
            }
            case SEND_FAILED -> {
                metrics.counter("recording.stop.reconciliation.send_failed").increment();
                log.error("AI STOP 송신에 실패해 기존 최종화·정리 경로를 수행했습니다. recordingSessionId={}, ownerCheck={}",
                        connection.recordingSessionId(), path);
                // Stop send failure performs AI cleanup/finalization in the existing service path.
                // Close only when the exact AI connection was removed; never close a replacement.
                if (connections.find(connection.recordingSessionId()).isEmpty()) {
                    audio.close(connection.recordingSessionId());
                }
            }
            case NOT_CURRENT -> metrics.counter("recording.stop.reconciliation.connection_replaced").increment();
            case OWNER_CHANGED -> metrics.counter("recording.stop.reconciliation.owner.mismatch").increment();
            case RECOVERY_FENCED -> metrics.counter("recording.stop.reconciliation.recovery_fenced").increment();
            case ALREADY_REQUESTED -> metrics.counter("recording.stop.reconciliation.already_requested").increment();
            case CONNECTION_INELIGIBLE -> metrics.counter("recording.stop.reconciliation.connection_ineligible").increment();
        }
    }

    private boolean isRedisUnavailable(DataAccessException exception) {
        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof QueryTimeoutException
                    || cause instanceof RedisConnectionFailureException) {
                return true;
            }
            String type = cause.getClass().getSimpleName();
            if (type.equals("RedisConnectionException") || type.equals("RedisCommandTimeoutException")) {
                return true;
            }
        }
        return false;
    }

    private boolean eligible(AiLiveMeetingConnection connection) {
        if (connection.owner() == null || connection.isRecoveryFenced() || connection.isClosed()
                || connection.isStopRequested()) {
            return false;
        }
        AiLiveMeetingConnectionState state = connection.state();
        return state == AiLiveMeetingConnectionState.READY || state == AiLiveMeetingConnectionState.PAUSED
                || state == AiLiveMeetingConnectionState.PAUSE_SENT
                || state == AiLiveMeetingConnectionState.RESUME_SENT;
    }
}
