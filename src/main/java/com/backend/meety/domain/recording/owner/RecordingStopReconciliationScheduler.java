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
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
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
    }

    @Scheduled(fixedDelayString = "${recording.stop-reconciliation.interval}",
            scheduler = SchedulingConfig.RECORDING_STOP_RECONCILIATION_SCHEDULER_BEAN_NAME)
    public void scan() {
        if (!properties.enabled()) {
            return;
        }
        List<AiLiveMeetingConnection> candidates = connections.findAll().stream()
                .filter(this::eligible)
                .toList();
        if (candidates.isEmpty()) {
            return;
        }
        metrics.counter("recording.stop.reconciliation.scans").increment();
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
                    metrics.counter("recording.stop.reconciliation.failures").increment();
                    log.warn("녹음 STOP 보정에 실패했습니다. recordingSessionId={}",
                            connection.recordingSessionId(), e);
                }
            }
        }
    }

    private Map<Long, RecordingStatusSnapshot> readStatuses(List<AiLiveMeetingConnection> batch) {
        try {
            Map<Long, RecordingStatusSnapshot> byId = new HashMap<>();
            metrics.counter("recording.stop.reconciliation.db.queries").increment();
            for (RecordingStatusSnapshot status : statuses.findStatuses(
                    batch.stream().map(AiLiveMeetingConnection::recordingSessionId).toList())) {
                byId.put(status.id(), status);
            }
            return byId;
        } catch (RuntimeException e) {
            metrics.counter("recording.stop.reconciliation.db.failures").increment();
            log.warn("녹음 STOP 보정용 Primary 상태 조회에 실패했습니다. candidateCount={}", batch.size(), e);
            return null;
        }
    }

    private void reconcile(AiLiveMeetingConnection connection, RecordingStatusSnapshot status) {
        if (status == null || status.status() != RecordingSessionStatus.COMPLETED
                || !connection.meetingId().equals(status.meetingId()) || !eligible(connection)
                || !ai.isCurrent(connection)) {
            return;
        }
        metrics.counter("recording.stop.reconciliation.detected").increment();
        String localOwner = connection.owner();
        Optional<String> currentOwner;
        try {
            currentOwner = owners.find(connection.recordingSessionId());
        } catch (DataAccessException e) {
            metrics.counter("recording.stop.reconciliation.owner.unavailable").increment();
            log.warn("Redis Owner 확인 불가로 STOP 보정을 보류합니다. recordingSessionId={}",
                    connection.recordingSessionId(), e);
            return;
        }
        if (currentOwner.isEmpty() || !currentOwner.get().equals(localOwner)) {
            metrics.counter("recording.stop.reconciliation.owner.mismatch").increment();
            log.warn("현재 Owner가 아니어서 STOP 보정을 보류합니다. recordingSessionId={}, localOwner={}, redisOwner={}",
                    connection.recordingSessionId(), localOwner, currentOwner.orElse(null));
            return;
        }
        if (!ai.stopCurrent(connection, localOwner)) {
            return;
        }
        audio.close(connection.recordingSessionId());
        metrics.counter("recording.stop.reconciliation.started").increment();
        log.warn("DB COMPLETED를 확인해 누락된 AI STOP을 보정했습니다. recordingSessionId={}, owner={}",
                connection.recordingSessionId(), localOwner);
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
