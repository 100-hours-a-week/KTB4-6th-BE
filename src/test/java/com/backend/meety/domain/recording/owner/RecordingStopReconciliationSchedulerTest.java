package com.backend.meety.domain.recording.owner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionState;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.realtime.AudioWebSocketCloser;
import com.backend.meety.domain.recording.repository.RecordingStatusSnapshot;
import com.backend.meety.domain.recording.service.RecordingStatusPrimaryReader;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;

class RecordingStopReconciliationSchedulerTest {

    private final AiLiveMeetingConnectionRegistry connections = mock(AiLiveMeetingConnectionRegistry.class);
    private final RecordingStatusPrimaryReader statuses = mock(RecordingStatusPrimaryReader.class);
    private final RecordingOwnerRepository owners = mock(RecordingOwnerRepository.class);
    private final AiLiveMeetingConnectionService ai = mock(AiLiveMeetingConnectionService.class);
    private final AudioWebSocketCloser audio = mock(AudioWebSocketCloser.class);
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();

    private RecordingStopReconciliationScheduler scheduler(boolean enabled) {
        return new RecordingStopReconciliationScheduler(
                new RecordingStopReconciliationProperties(enabled, Duration.ofSeconds(3), false, Duration.ofSeconds(10)),
                connections, statuses, owners, ai, audio, metrics);
    }

    private RecordingStopReconciliationScheduler controlScheduler(boolean enabled) {
        return new RecordingStopReconciliationScheduler(
                new RecordingStopReconciliationProperties(false, Duration.ofSeconds(3), enabled, Duration.ofSeconds(10)),
                connections, statuses, owners, ai, audio, metrics);
    }

    @Test
    void disabledDoesNotReadConnectionsOrDatabase() {
        scheduler(false).scan();
        verifyNoInteractions(connections, statuses, owners, ai, audio);
    }

    @Test
    void recordingStillActiveDoesNotStopAi() {
        AiLiveMeetingConnection connection = candidate(88L, AiLiveMeetingConnectionState.READY);
        when(connections.findAll()).thenReturn(List.of(connection));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.RECORDING)));

        scheduler(true).scan();

        verifyNoInteractions(owners, audio);
        verify(ai, never()).stopCurrent(connection, "server-a:1");
    }

    @Test
    void completedReadyAndPausedConnectionsStopOnlyOnce() {
        AiLiveMeetingConnection ready = candidate(88L, AiLiveMeetingConnectionState.READY);
        AiLiveMeetingConnection paused = candidate(89L, AiLiveMeetingConnectionState.PAUSED);
        when(connections.findAll()).thenReturn(List.of(ready, paused));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.COMPLETED),
                new RecordingStatusSnapshot(89L, 42L, RecordingSessionStatus.COMPLETED)));
        when(owners.find(88L)).thenReturn(Optional.of("server-a:1"));
        when(owners.find(89L)).thenReturn(Optional.of("server-a:1"));
        when(ai.isCurrent(ready)).thenReturn(true);
        when(ai.isCurrent(paused)).thenReturn(true);
        when(ai.stopCurrent(ready, "server-a:1")).thenReturn(true);
        when(ai.stopCurrent(paused, "server-a:1")).thenReturn(true);

        scheduler(true).scan();

        verify(audio).close(88L);
        verify(audio).close(89L);
        assertThat(metrics.counter("recording.stop.reconciliation.started").count()).isEqualTo(2);
    }

    @Test
    void stopSentEndedAndFencedConnectionsAreSkipped() {
        AiLiveMeetingConnection stopping = candidate(88L, AiLiveMeetingConnectionState.STOP_SENT);
        AiLiveMeetingConnection ended = candidate(89L, AiLiveMeetingConnectionState.ENDED);
        AiLiveMeetingConnection fenced = candidate(90L, AiLiveMeetingConnectionState.READY);
        when(fenced.isRecoveryFenced()).thenReturn(true);
        when(connections.findAll()).thenReturn(List.of(stopping, ended, fenced));

        scheduler(true).scan();

        verifyNoInteractions(statuses, owners, ai, audio);
    }

    @Test
    void ownerMismatchAndRedisOutageNeverStopOldConnection() {
        AiLiveMeetingConnection old = candidate(88L, AiLiveMeetingConnectionState.READY);
        AiLiveMeetingConnection unavailable = candidate(89L, AiLiveMeetingConnectionState.READY);
        when(connections.findAll()).thenReturn(List.of(old, unavailable));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.COMPLETED),
                new RecordingStatusSnapshot(89L, 42L, RecordingSessionStatus.COMPLETED)));
        when(ai.isCurrent(old)).thenReturn(true);
        when(ai.isCurrent(unavailable)).thenReturn(true);
        when(owners.find(88L)).thenReturn(Optional.of("server-b:2"));
        when(owners.find(89L)).thenThrow(new RedisConnectionFailureException("down"));

        scheduler(true).scan();

        verify(ai, never()).stopCurrent(old, "server-a:1");
        verify(ai, never()).stopCurrent(unavailable, "server-a:1");
        verifyNoInteractions(audio);
    }

    @Test
    void failedDatabaseBatchDoesNotPreventLaterBatch() {
        List<AiLiveMeetingConnection> batch = new ArrayList<>();
        for (long id = 1; id <= 101; id++) {
            batch.add(candidate(id, AiLiveMeetingConnectionState.READY));
        }
        AiLiveMeetingConnection last = batch.getLast();
        when(connections.findAll()).thenReturn(batch);
        when(statuses.findStatuses(anyCollection()))
                .thenThrow(new IllegalStateException("DB unavailable"))
                .thenReturn(List.of(new RecordingStatusSnapshot(101L, 42L, RecordingSessionStatus.COMPLETED)));
        when(ai.isCurrent(last)).thenReturn(true);
        when(owners.find(101L)).thenReturn(Optional.of("server-a:1"));
        when(ai.stopCurrent(last, "server-a:1")).thenReturn(true);

        scheduler(true).scan();

        verify(audio).close(101L);
        assertThat(metrics.counter("recording.stop.reconciliation.db.failures").count()).isEqualTo(1);
    }

    @Test
    void replacedLocalConnectionIsNotStopped() {
        AiLiveMeetingConnection connection = candidate(88L, AiLiveMeetingConnectionState.READY);
        when(connections.findAll()).thenReturn(List.of(connection));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.COMPLETED)));
        when(ai.isCurrent(connection)).thenReturn(false);

        scheduler(true).scan();

        verifyNoInteractions(owners, audio);
    }

    @Test
    void databasePausedAndAiReadySendsPauseOnlyToVerifiedOwner() {
        AiLiveMeetingConnection connection = candidate(88L, AiLiveMeetingConnectionState.READY);
        when(connections.findAll()).thenReturn(List.of(connection));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.PAUSED)));
        when(ai.isCurrent(connection)).thenReturn(true);
        when(owners.find(88L)).thenReturn(Optional.of("server-a:1"));
        when(ai.reconcilePause(connection, "server-a:1")).thenReturn(true);

        controlScheduler(true).scan();

        verify(ai).reconcilePause(connection, "server-a:1");
        verify(ai, never()).reconcileResume(connection, "server-a:1");
        assertThat(metrics.counter("recording.control.reconciliation.pause.sent").count()).isEqualTo(1);
    }

    @Test
    void databaseRecordingAndAiPausedSendsResume() {
        AiLiveMeetingConnection connection = candidate(88L, AiLiveMeetingConnectionState.PAUSED);
        when(connections.findAll()).thenReturn(List.of(connection));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.RECORDING)));
        when(ai.isCurrent(connection)).thenReturn(true);
        when(owners.find(88L)).thenReturn(Optional.of("server-a:1"));
        when(ai.reconcileResume(connection, "server-a:1")).thenReturn(true);

        controlScheduler(true).scan();

        verify(ai).reconcileResume(connection, "server-a:1");
        verify(ai, never()).reconcilePause(connection, "server-a:1");
    }

    @Test
    void alignedStateAndTransitionInFlightDoNotSendDuplicateCommands() {
        AiLiveMeetingConnection aligned = candidate(88L, AiLiveMeetingConnectionState.PAUSED);
        AiLiveMeetingConnection pending = candidate(89L, AiLiveMeetingConnectionState.PAUSE_SENT);
        when(connections.findAll()).thenReturn(List.of(aligned, pending));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.PAUSED),
                new RecordingStatusSnapshot(89L, 42L, RecordingSessionStatus.PAUSED)));
        when(ai.isCurrent(aligned)).thenReturn(true);
        when(ai.isCurrent(pending)).thenReturn(true);
        when(owners.find(89L)).thenReturn(Optional.of("server-a:1"));

        controlScheduler(true).scan();

        verifyNoInteractions(audio);
        verify(ai, never()).reconcilePause(aligned, "server-a:1");
        verify(ai, never()).reconcileResume(aligned, "server-a:1");
        verify(ai, never()).reconcilePause(pending, "server-a:1");
        verify(ai, never()).reconcileResume(pending, "server-a:1");
    }

    @Test
    void controlReconciliationDisabledDoesNotReadState() {
        controlScheduler(false).scan();
        verifyNoInteractions(connections, statuses, owners, ai, audio);
    }

    @Test
    void completedStateRemainsStopPriorityAndDoesNotPauseOrResume() {
        AiLiveMeetingConnection connection = candidate(88L, AiLiveMeetingConnectionState.READY);
        when(connections.findAll()).thenReturn(List.of(connection));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.COMPLETED)));
        when(ai.isCurrent(connection)).thenReturn(true);

        controlScheduler(true).scan();

        verify(owners, never()).find(88L);
        verify(ai, never()).reconcilePause(connection, "server-a:1");
        verify(ai, never()).reconcileResume(connection, "server-a:1");
    }

    @Test
    void pauseAckAfterConcurrentResumeIsReconciledTowardLatestDatabaseState() {
        AiLiveMeetingConnection connection = mock(AiLiveMeetingConnection.class);
        AtomicReference<AiLiveMeetingConnectionState> state = new AtomicReference<>(AiLiveMeetingConnectionState.READY);
        when(connection.recordingSessionId()).thenReturn(88L);
        when(connection.meetingId()).thenReturn(42L);
        when(connection.owner()).thenReturn("server-a:1");
        when(connection.state()).thenAnswer(ignored -> state.get());
        when(connection.isStopRequested()).thenReturn(false);
        when(connection.isRecoveryFenced()).thenReturn(false);
        when(connection.isClosed()).thenReturn(false);
        when(connection.claimControlAckTimeout(Duration.ofSeconds(10))).thenReturn(false);
        when(connections.findAll()).thenReturn(List.of(connection));
        when(ai.isCurrent(connection)).thenReturn(true);
        when(owners.find(88L)).thenReturn(Optional.of("server-a:1"));
        when(ai.reconcilePause(connection, "server-a:1")).thenAnswer(call -> {
            state.set(AiLiveMeetingConnectionState.PAUSE_SENT);
            return true;
        });
        when(ai.reconcileResume(connection, "server-a:1")).thenAnswer(call -> {
            state.set(AiLiveMeetingConnectionState.RESUME_SENT);
            return true;
        });
        RecordingStopReconciliationScheduler scheduler = controlScheduler(true);

        // Scanner observed PAUSED and sent PAUSE, then the user changed the desired state to RECORDING.
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.PAUSED)));
        scheduler.scan();
        state.set(AiLiveMeetingConnectionState.PAUSED); // AI acknowledged the in-flight PAUSE.

        // Fresh primary read after ACK sees the user's newer RESUME state and sends RESUME.
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.RECORDING)));
        scheduler.scan();
        state.set(AiLiveMeetingConnectionState.READY); // AI acknowledged RESUME.
        scheduler.scan();

        verify(ai).reconcilePause(connection, "server-a:1");
        verify(ai).reconcileResume(connection, "server-a:1");
        assertThat(state.get()).isEqualTo(AiLiveMeetingConnectionState.READY);
    }

    @Test
    void pauseAckTimeoutRequestsRecoveryOnlyOnce() {
        AiLiveMeetingConnection connection = candidate(88L, AiLiveMeetingConnectionState.PAUSE_SENT);
        when(connection.claimControlAckTimeout(Duration.ofSeconds(10))).thenReturn(true, false);
        when(connections.findAll()).thenReturn(List.of(connection));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.PAUSED)));
        when(ai.isCurrent(connection)).thenReturn(true);
        when(owners.find(88L)).thenReturn(Optional.of("server-a:1"));

        RecordingStopReconciliationScheduler scheduler = controlScheduler(true);
        scheduler.scan();
        scheduler.scan();

        verify(ai).controlAckTimedOut(connection);
    }

    @Test
    void ownerMismatchOrRedisFailureDefersPauseResumeCorrection() {
        AiLiveMeetingConnection mismatch = candidate(88L, AiLiveMeetingConnectionState.READY);
        AiLiveMeetingConnection unavailable = candidate(89L, AiLiveMeetingConnectionState.READY);
        when(connections.findAll()).thenReturn(List.of(mismatch, unavailable));
        when(statuses.findStatuses(anyCollection())).thenReturn(List.of(
                new RecordingStatusSnapshot(88L, 42L, RecordingSessionStatus.PAUSED),
                new RecordingStatusSnapshot(89L, 42L, RecordingSessionStatus.PAUSED)));
        when(ai.isCurrent(mismatch)).thenReturn(true);
        when(ai.isCurrent(unavailable)).thenReturn(true);
        when(owners.find(88L)).thenReturn(Optional.of("server-b:2"));
        when(owners.find(89L)).thenThrow(new RedisConnectionFailureException("down"));

        controlScheduler(true).scan();

        verify(ai, never()).reconcilePause(mismatch, "server-a:1");
        verify(ai, never()).reconcilePause(unavailable, "server-a:1");
        assertThat(metrics.counter("recording.control.reconciliation.owner.mismatch").count()).isEqualTo(1);
        assertThat(metrics.counter("recording.control.reconciliation.owner.unavailable").count()).isEqualTo(1);
    }

    private AiLiveMeetingConnection candidate(Long id, AiLiveMeetingConnectionState state) {
        AiLiveMeetingConnection connection = mock(AiLiveMeetingConnection.class);
        AtomicBoolean stopped = new AtomicBoolean();
        when(connection.recordingSessionId()).thenReturn(id);
        when(connection.meetingId()).thenReturn(42L);
        when(connection.owner()).thenReturn("server-a:1");
        when(connection.state()).thenReturn(state);
        when(connection.isStopRequested()).thenAnswer(ignored -> stopped.get());
        return connection;
    }
}
