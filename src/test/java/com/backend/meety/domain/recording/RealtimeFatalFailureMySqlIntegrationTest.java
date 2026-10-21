package com.backend.meety.domain.recording;

import static com.backend.meety.domain.recording.RecordingFixtures.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.backend.meety.domain.ai.event.*;
import com.backend.meety.domain.ai.realtime.*;
import com.backend.meety.domain.credit.TestCreditPolicy;
import com.backend.meety.domain.credit.entity.*;
import com.backend.meety.domain.credit.repository.*;
import com.backend.meety.domain.meeting.entity.*;
import com.backend.meety.domain.meeting.event.MeetingCompletedEvent;
import com.backend.meety.domain.meeting.realtime.MeetingSsePublisher;
import com.backend.meety.domain.meeting.realtime.MeetingSseCleanupEventListener;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.recording.entity.*;
import com.backend.meety.domain.recording.event.RecordingFailedEvent;
import com.backend.meety.domain.recording.owner.RecordingOwnerRepository;
import com.backend.meety.domain.recording.realtime.*;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.recording.service.*;
import com.backend.meety.domain.team.entity.*;
import com.backend.meety.domain.user.entity.User;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.*;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;

@SpringJUnitConfig({RecordingMySqlIntegrationTest.DatabaseConfig.class, RealtimeFatalFailureMySqlIntegrationTest.Config.class})
@EnabledIfEnvironmentVariable(named = "RECORDING_TEST_DB_URL", matches = ".+")
class RealtimeFatalFailureMySqlIntegrationTest {
    @Configuration(proxyBeanMethods = false)
    static class Config {
        @Bean RecordingOwnerRepository fatalOwners() { return mock(RecordingOwnerRepository.class); }
        @Bean AiRecoveryRegistry fatalRecoveries() { return new AiRecoveryRegistry(); }
        @Bean AiLiveMeetingConnectionRegistry fatalConnections(AiRecoveryRegistry r) { return new AiLiveMeetingConnectionRegistry(r); }
        @Bean AiLiveMeetingConnectionService fatalAi() { return mock(AiLiveMeetingConnectionService.class); }
        @Bean AudioWebSocketRegistry fatalAudio() { return new AudioWebSocketRegistry(); }
        @Bean MeterRegistry fatalMeters() { return new SimpleMeterRegistry(); }
        @Bean MeetingSsePublisher fatalSsePublisher() { return mock(MeetingSsePublisher.class); }
        @Bean RealtimeFatalFailureService fatalService(RecordingSessionRepository r, MeetingRepository m,
                RecordingOwnerRepository o, AiLiveMeetingConnectionRegistry c, AiRecoveryRegistry recovery,
                TeamCreditRepository credits, CreditLedgerRepository ledgers, MeterRegistry metrics, ApplicationEventPublisher events) {
            return new RealtimeFatalFailureService(r, m, o, c, recovery, credits, ledgers, CLOCK, metrics, events);
        }
        @Bean RealtimeFatalFailureListener fatalListener(AiLiveMeetingConnectionService ai, AudioWebSocketRegistry audio,
                RecordingOwnerRepository owners, MeterRegistry metrics) {
            return new RealtimeFatalFailureListener(ai, audio, owners, new ObjectMapper(), metrics);
        }
        @Bean RecordingSseEventListener fatalSse(MeetingSsePublisher p) { return new RecordingSseEventListener(p); }
        @Bean MeetingSseCleanupEventListener meetingCleanup(MeetingSsePublisher p) { return new MeetingSseCleanupEventListener(p); }
        @Bean Probe fatalProbe() { return new Probe(); }
    }
    static class Probe {
        final AtomicBoolean reject = new AtomicBoolean();
        final List<Object> completedEvents = new java.util.concurrent.CopyOnWriteArrayList<>();
        @EventListener public void observe(RecordingFailedEvent event) {
            if (reject.get()) { throw new IllegalStateException("force rollback"); }
        }
        @EventListener public void completed(MeetingCompletedEvent event) { completedEvents.add(event); }
        @EventListener public void recordingCompleted(com.backend.meety.domain.recording.event.RecordingCompletedEvent event) {
            completedEvents.add(event);
        }
        @EventListener public void transcriptFinalized(MeetingTranscriptFinalizedEvent event) { completedEvents.add(event); }
    }
    @Autowired DataSource dataSource;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired ApplicationEventPublisher events;
    @Autowired RealtimeFatalFailureService fatal;
    @Autowired RecordingService recordings;
    @Autowired RecordingOwnerRepository owners;
    @Autowired AiLiveMeetingConnectionRegistry connections;
    @Autowired AiRecoveryRegistry recoveries;
    @Autowired AiLiveMeetingConnectionService ai;
    @Autowired AudioWebSocketRegistry audio;
    @Autowired MeetingSsePublisher sse;
    @Autowired Probe probe;
    @Autowired TeamCreditRepository credits;
    @PersistenceContext EntityManager em;
    JdbcTemplate jdbc;
    TransactionTemplate tx;
    long id;
    long meetingId;
    long userId;
    long teamId;
    WebSocketSession fe;

    @BeforeEach void prepare() throws Exception {
        jdbc = new JdbcTemplate(dataSource); tx = new TransactionTemplate(transactionManager);
        reset(owners, ai, sse); probe.reject.set(false); probe.completedEvents.clear();
        for (var c : connections.findAll()) { connections.remove(c.recordingSessionId(), c); }
        for (String table : List.of("credit_ledgers", "recording_sessions", "meeting_participants", "meetings",
                "team_credits", "team_members", "teams", "users")) { jdbc.update("delete from " + table); }
        tx.executeWithoutResult(status -> {
            var user = User.create(); var team = Team.create("fatal test"); em.persist(user); em.persist(team);
            var member = TeamMember.createLeader(user, team, "owner"); em.persist(member);
            var meeting = Meeting.create(team, member, "fatal", "test", null, NOW, 30); em.persist(meeting);
            em.persist(MeetingParticipant.create(meeting, member)); meeting.start(NOW);
            var session = RecordingSession.start(meeting, member, NOW); em.persist(session);
            var credit = TeamCredit.create(team, 100, TestCreditPolicy.MAX_BALANCE);
            credit.use(TestCreditPolicy.RECORDING_COST); em.persist(credit);
            em.persist(CreditLedger.useForRecording(team, session.getId(), TestCreditPolicy.RECORDING_COST, credit.getBalance()));
            id = session.getId(); meetingId = meeting.getId(); userId = user.getId(); teamId = team.getId();
        });
        var connection = new AiLiveMeetingConnection(id, meetingId, "current");
        connection.assignOwner("owner"); connection.markReady(); connections.reserve(connection);
        when(owners.find(id)).thenReturn(Optional.of("owner"));
        fe = mock(WebSocketSession.class); when(fe.isOpen()).thenReturn(true); audio.replace(id, fe);
        doAnswer(call -> {
            assertThat(state()).isEqualTo("FAILED");
            assertThat(meetingState()).isEqualTo("COMPLETED");
            assertThat(restoreCount()).isEqualTo(1);
            return null;
        }).when(fe).sendMessage(any());
        doAnswer(call -> {
            assertThat(state()).isEqualTo("FAILED"); assertThat(meetingState()).isEqualTo("COMPLETED"); return null;
        })
                .when(sse).broadcast(eq(meetingId), eq("RECORDING_FAILED"), any());
    }
    AiFatalFailureEvent signal() { return new AiFatalFailureEvent(id, "owner", "current", RealtimeFailureReason.AI_PROCESSING_ERROR); }
    String state() { return jdbc.queryForObject("select status from recording_sessions where id = ?", String.class, id); }
    String meetingState() { return jdbc.queryForObject("select status from meetings where id = ?", String.class, meetingId); }
    long balance() { return jdbc.queryForObject("select balance from team_credits where team_id = ?", Long.class, teamId); }
    long restoreCount() { return jdbc.queryForObject("select count(*) from credit_ledgers where idempotency_key = ?",
            Long.class, CreditLedger.keyOf(CreditTransactionType.RESTORE, CreditSourceType.RECORDING, id)); }

    @Test @DisplayName("즉시 Fatal은 FAILED 커밋 뒤에만 FE 오류와 종료 및 참가자 SSE를 한 번 전파한다")
    void committedFailurePropagates() throws Exception {
        // 테스트 목적:
        // FE 확인 없이 서버 상태를 확정하고 커밋 전 실패 상태를 전파하지 않는지 검증한다.
        // given
        var signal = signal();
        // when
        fatal.onFatal(signal); fatal.onFatal(signal);
        // then
        assertThat(state()).isEqualTo("FAILED");
        assertThat(meetingState()).isEqualTo("COMPLETED");
        assertThat(balance()).isEqualTo(100); assertThat(restoreCount()).isEqualTo(1);
        assertThat(jdbc.queryForObject("select amount from credit_ledgers where idempotency_key = ?", Long.class,
                CreditLedger.keyOf(CreditTransactionType.RESTORE, CreditSourceType.RECORDING, id))).isEqualTo(20);
        assertThat(jdbc.queryForObject("select ended_at from meetings where id = ?", java.time.LocalDateTime.class, meetingId))
                .isEqualTo(jdbc.queryForObject("select ended_at from recording_sessions where id = ?", java.time.LocalDateTime.class, id));
        verify(fe).sendMessage(argThat(message -> message instanceof TextMessage text
                && text.getPayload().contains("STOP_RECORDING") && text.getPayload().contains("AI_PROCESSING_ERROR")));
        verify(fe).close(CloseStatus.SERVER_ERROR);
        verify(owners).deleteIfOwner(id, "owner");
        verify(sse).broadcast(meetingId, "RECORDING_FAILED",
                new RecordingFailedSseEvent("RECORDING_FAILED", id, RealtimeFailureReason.AI_PROCESSING_ERROR));
        verify(ai).closeForFatal(id, "owner");
        assertThat(probe.completedEvents).containsExactly(new MeetingCompletedEvent(meetingId));
        var order = inOrder(fe, sse);
        order.verify(fe).sendMessage(any()); order.verify(fe).close(CloseStatus.SERVER_ERROR);
        order.verify(sse).broadcast(eq(meetingId), eq("RECORDING_FAILED"), any());
        order.verify(sse).completeAll(meetingId);
    }

    @ParameterizedTest @EnumSource(value = RealtimeFailureReason.class, names = {"AUDIO_ACK_TIMEOUT", "AI_HEARTBEAT_TIMEOUT"})
    @DisplayName("Recovery 소진 이벤트는 최초 timeout code를 유지해 Fatal로 확정한다")
    void exhaustionPreservesReason(RealtimeFailureReason reason) {
        // 테스트 목적:
        // 단순 RECOVERY_EXHAUSTED 대신 실제 장애 원인이 참가자에게 전달되는지 검증한다.
        // given
        var event = new AiRecoveryExhaustedEvent(id, "generation", "owner", "current", 2, reason.name());
        // when
        events.publishEvent(event);
        // then
        assertThat(state()).isEqualTo("FAILED");
        verify(sse).broadcast(meetingId, "RECORDING_FAILED", new RecordingFailedSseEvent("RECORDING_FAILED", id, reason));
    }

    @Test @DisplayName("ACK 및 Heartbeat timeout 자체는 Fatal listener가 소비하지 않는다")
    void timeoutsAreNotImmediateFatal() {
        // 테스트 목적:
        // #319/#320 최초 timeout이 #321을 우회해 FAILED로 전환되지 않는지 검증한다.
        // given
        var now = CLOCK.instant();
        // when
        events.publishEvent(new AiAudioAckTimeoutEvent(id, "current", 101, 100, now));
        events.publishEvent(new AiHeartbeatTimeoutEvent(id, "current", "hb", 2, now));
        // then
        assertThat(state()).isEqualTo("RECORDING"); verifyNoInteractions(sse, ai);
    }

    @Test @DisplayName("Fatal 트랜잭션 rollback은 FAILED·FE 전파·Owner 해제·SSE를 모두 취소한다")
    void rollbackHasNoExternalSideEffects() throws Exception {
        // 테스트 목적:
        // 상태 확정 실패가 성공한 종료처럼 외부에 전달되지 않는지 검증한다.
        // given
        probe.reject.set(true);
        // when
        assertThatThrownBy(() -> fatal.onFatal(signal())).isInstanceOf(IllegalStateException.class);
        // then
        assertThat(state()).isEqualTo("RECORDING");
        assertThat(meetingState()).isEqualTo("IN_PROGRESS");
        assertThat(balance()).isEqualTo(80); assertThat(restoreCount()).isZero();
        verify(fe, never()).sendMessage(any()); verify(fe, never()).close(any());
        verify(owners, never()).deleteIfOwner(anyLong(), anyString()); verifyNoInteractions(sse, ai);
    }

    @ParameterizedTest @EnumSource(value = RecordingSessionStatus.class, names = {"COMPLETED", "FAILED"})
    @DisplayName("이미 종료된 녹음에는 늦은 Fatal이 상태나 이벤트를 덮어쓰지 않는다")
    void terminalStateIgnoresLateFatal(RecordingSessionStatus terminal) {
        // 테스트 목적:
        // 기존 terminal 상태와 한 번 처리된 실패의 멱등성이 보장되는지 검증한다.
        // given
        jdbc.update("update recording_sessions set status = ? where id = ?", terminal.name(), id);
        // when
        fatal.onFatal(signal());
        // then
        assertThat(state()).isEqualTo(terminal.name()); verifyNoInteractions(sse, ai);
        assertThat(balance()).isEqualTo(80); assertThat(restoreCount()).isZero();
    }

    @Test @DisplayName("이전 Owner 또는 이전 connection의 늦은 Fatal은 현재 녹음을 종료하지 않는다")
    void staleSourceIsIgnored() {
        // 테스트 목적:
        // 새 Owner와 새 실행의 상태를 이전 세대의 오류가 변경하지 않는지 검증한다.
        // given
        var oldConnection = new AiFatalFailureEvent(id, "owner", "old", RealtimeFailureReason.AI_PROCESSING_ERROR);
        // when
        fatal.onFatal(oldConnection);
        when(owners.find(id)).thenReturn(Optional.of("new-owner"));
        fatal.onFatal(signal());
        // then
        assertThat(state()).isEqualTo("RECORDING"); verifyNoInteractions(sse, ai);
    }

    @Test @DisplayName("새 Recovery generation이 존재하면 이전 소진 이벤트는 현재 녹음을 실패시키지 않는다")
    void oldExhaustionCannotTerminateNewGeneration() {
        // 테스트 목적:
        // connection id가 같더라도 이전 Recovery episode의 종료 통지를 소비하지 않는지 검증한다.
        // given
        recoveries.begin(connections.find(id).orElseThrow(), "AI_UNEXPECTED_CLOSE", CLOCK.instant());
        // when
        fatal.onRecoveryExhausted(new AiRecoveryExhaustedEvent(id, "old-generation", "owner", "current", 2, "AUDIO_ACK_TIMEOUT"));
        // then
        assertThat(state()).isEqualTo("RECORDING"); verifyNoInteractions(sse, ai);
    }

    @Test @DisplayName("같은 Owner의 수락된 전사 실패는 AI connection 교체 후에도 세션을 Fatal로 확정한다")
    void acceptedTranscriptFailureSurvivesReplacement() {
        // 테스트 목적:
        // 이전 큐의 데이터 무결성 실패를 단순 stale network callback으로 버리지 않는지 검증한다.
        // given
        var previous = connections.find(id).orElseThrow(); connections.remove(id, previous);
        var next = new AiLiveMeetingConnection(id, meetingId, "next"); next.assignOwner("owner");
        next.markReady(); connections.reserve(next);
        // when
        fatal.onFatal(new AiFatalFailureEvent(id, "owner", null, RealtimeFailureReason.TRANSCRIPT_PROCESSING_FAILED));
        // then
        assertThat(state()).isEqualTo("FAILED");
        verify(sse).broadcast(meetingId, "RECORDING_FAILED",
                new RecordingFailedSseEvent("RECORDING_FAILED", id, RealtimeFailureReason.TRANSCRIPT_PROCESSING_FAILED));
    }

    @Test @DisplayName("Fatal이 먼저 확정되면 늦은 사용자 STOP은 FAILED를 COMPLETED로 덮어쓰지 못한다")
    void stopAfterFatalCannotOverwrite() {
        // 테스트 목적:
        // 경쟁의 Fatal 우선 순서에서도 정상 종료 API가 실패 상태를 되돌리지 못하는지 검증한다.
        // given
        fatal.onFatal(signal());
        // when
        assertThatThrownBy(() -> recordings.updateStatus(userId, id, RecordingSessionStatus.COMPLETED))
                .isInstanceOf(com.backend.meety.domain.recording.exception.RecordingException.class);
        // then
        assertThat(state()).isEqualTo("FAILED");
        verify(sse, never()).broadcast(eq(meetingId), eq("RECORDING_COMPLETED"), any());
    }

    @Test @DisplayName("FE send와 Owner 해제가 실패해도 FAILED와 참가자 SSE는 유지된다")
    void externalFailuresDoNotRollback() throws Exception {
        // 테스트 목적:
        // 외부 I/O 예외가 서버 종료 확정 및 다른 참가자 전파를 막지 않는지 검증한다.
        // given
        doThrow(new java.io.IOException("closed")).when(fe).sendMessage(any());
        when(owners.deleteIfOwner(id, "owner")).thenThrow(new IllegalStateException("redis unavailable"));
        // when
        fatal.onFatal(signal());
        // then
        assertThat(state()).isEqualTo("FAILED"); verify(fe).close(CloseStatus.SERVER_ERROR);
        verify(sse).broadcast(eq(meetingId), eq("RECORDING_FAILED"), any());
    }

    @Test @DisplayName("FE와 AI가 이미 닫혀 있어도 서버 FAILED와 SSE를 완료한다")
    void closedSocketsStillFail() {
        // 테스트 목적:
        // 소켓 생존 여부가 서버 종료 조건이 되지 않는지 검증한다.
        // given
        when(fe.isOpen()).thenReturn(false); connections.find(id).orElseThrow().close();
        // when
        fatal.onRecoveryExhausted(new AiRecoveryExhaustedEvent(id, "g", "owner", "current", 2, "AI_UNEXPECTED_CLOSE"));
        // then
        assertThat(state()).isEqualTo("FAILED"); verify(sse).broadcast(eq(meetingId), eq("RECORDING_FAILED"), any());
    }

    @Test @DisplayName("서로 다른 Fatal 원인이 동시에 도착해도 FAILED와 이벤트를 한 번만 선점한다")
    void concurrentFatalWinsOnce() throws Exception {
        // 테스트 목적:
        // MySQL 행 잠금이 다른 오류 원인 간 중복 최종 처리를 막는지 검증한다.
        // given
        var other = new AiFatalFailureEvent(id, "owner", "current", RealtimeFailureReason.AI_INBOUND_PROCESSING_FAILED);
        // when
        race(() -> fatal.onFatal(signal()), () -> fatal.onFatal(other));
        // then
        assertThat(state()).isEqualTo("FAILED"); verify(sse).broadcast(eq(meetingId), eq("RECORDING_FAILED"), any());
        verify(owners).deleteIfOwner(id, "owner");
        assertThat(balance()).isEqualTo(100); assertThat(restoreCount()).isEqualTo(1);
    }

    @Test @DisplayName("Fatal과 사용자 STOP 경쟁은 MySQL에서 하나의 terminal 상태와 전파만 확정한다")
    void stopAndFatalRace() throws Exception {
        // 테스트 목적:
        // 정상 완료와 시스템 실패가 서로의 최종 상태를 덮어쓰지 않는지 검증한다.
        // given
        Runnable stop = () -> {
            try { recordings.updateStatus(userId, id, RecordingSessionStatus.COMPLETED); }
            catch (com.backend.meety.domain.recording.exception.RecordingException expected) {
                assertThat(state()).isEqualTo("FAILED");
            }
        };
        // when
        race(() -> fatal.onFatal(signal()), stop);
        // then
        assertThat(state()).isIn("COMPLETED", "FAILED");
        assertThat(meetingState()).isEqualTo("COMPLETED");
        assertThat(balance()).isEqualTo(state().equals("FAILED") ? 100 : 80);
        assertThat(restoreCount()).isEqualTo(state().equals("FAILED") ? 1 : 0);
        verify(sse, times(state().equals("FAILED") ? 1 : 0)).broadcast(eq(meetingId), eq("RECORDING_FAILED"), any());
        verify(sse, times(state().equals("COMPLETED") ? 1 : 0)).broadcast(eq(meetingId), eq("RECORDING_COMPLETED"), any());
    }

    @Test @DisplayName("Fatal 보상은 적립 상한에 도달했어도 실제 차감 20을 전액 복원한다")
    void compensationIsNotCappedByGrantLimit() {
        // 테스트 목적:
        // 정기 적립의 상한 정책 때문에 서비스 실패 보상 일부가 사라지지 않는지 검증한다.
        // given
        jdbc.update("update team_credits set balance = ? where team_id = ?", TestCreditPolicy.MAX_BALANCE, teamId);
        // when
        fatal.onFatal(signal());
        // then
        assertThat(balance()).isEqualTo(TestCreditPolicy.MAX_BALANCE + 20);
        assertThat(jdbc.queryForObject("select balance_after from credit_ledgers where idempotency_key = ?", Long.class,
                CreditLedger.keyOf(CreditTransactionType.RESTORE, CreditSourceType.RECORDING, id)))
                .isEqualTo(TestCreditPolicy.MAX_BALANCE + 20);
    }

    @Test @DisplayName("이미 보상된 원장이 있으면 Fatal 종료만 수행하고 중복 지급하지 않는다")
    void existingRestoreDoesNotPayTwice() {
        // 테스트 목적:
        // 기존 멱등키와 팀 잔액 잠금으로 별도 지급된 보상도 중복 반영하지 않는지 검증한다.
        // given
        tx.executeWithoutResult(status -> {
            var credit = credits.findByTeamIdForUpdate(teamId).orElseThrow(); credit.restore(20);
            em.persist(CreditLedger.restoreForRecording(credit.getTeam(), id, 20, credit.getBalance()));
        });
        // when
        fatal.onFatal(signal());
        // then
        assertThat(state()).isEqualTo("FAILED"); assertThat(meetingState()).isEqualTo("COMPLETED");
        assertThat(balance()).isEqualTo(100); assertThat(restoreCount()).isEqualTo(1);
    }

    @Test @DisplayName("실제 녹음 차감 원장이 없으면 임의 크레딧을 지급하지 않고 Fatal 종료한다")
    void missingChargeDoesNotCreateCredit() throws Exception {
        // 테스트 목적:
        // 지불하지 않은 세션에 보상 명목으로 새 크레딧이 생성되지 않는지 검증한다.
        // given
        jdbc.update("delete from credit_ledgers where idempotency_key = ?",
                CreditLedger.keyOf(CreditTransactionType.USE, CreditSourceType.RECORDING, id));
        doNothing().when(fe).sendMessage(any());
        // when
        fatal.onFatal(signal());
        // then
        assertThat(state()).isEqualTo("FAILED"); assertThat(meetingState()).isEqualTo("COMPLETED");
        assertThat(balance()).isEqualTo(80); assertThat(restoreCount()).isZero();
        verify(sse).broadcast(eq(meetingId), eq("RECORDING_FAILED"), any());
    }

    @Test @DisplayName("보상 대상 팀 크레딧이 없으면 녹음·회의·원장 변경과 외부 전파를 모두 rollback한다")
    void compensationFailureRollsBackEverything() throws Exception {
        // 테스트 목적:
        // 보상 실패인데 상태만 종료되거나 성공 SSE가 나가는 부분 반영을 방지하는지 검증한다.
        // given
        jdbc.update("delete from team_credits where team_id = ?", teamId);
        // when
        assertThatThrownBy(() -> fatal.onFatal(signal()))
                .isInstanceOf(com.backend.meety.domain.credit.exception.CreditException.class);
        // then
        assertThat(state()).isEqualTo("RECORDING"); assertThat(meetingState()).isEqualTo("IN_PROGRESS");
        assertThat(restoreCount()).isZero(); verify(fe, never()).sendMessage(any()); verifyNoInteractions(sse, ai);
        verify(owners, never()).deleteIfOwner(anyLong(), anyString());
    }

    @Test @DisplayName("Fatal 보상과 다른 크레딧 차감이 동시에 실행돼도 잔액 변경을 잃지 않는다")
    void concurrentDebitAndCompensationAreSerialized() throws Exception {
        // 테스트 목적:
        // 서로 다른 크레딧 사용과 보상이 같은 팀 잔액 행 잠금을 재사용하는지 검증한다.
        // given
        Runnable debit = () -> tx.executeWithoutResult(status -> credits.findByTeamIdForUpdate(teamId).orElseThrow().use(1));
        // when
        race(() -> fatal.onFatal(signal()), debit);
        // then
        assertThat(balance()).isEqualTo(99); assertThat(restoreCount()).isEqualTo(1);
        assertThat(state()).isEqualTo("FAILED"); assertThat(meetingState()).isEqualTo("COMPLETED");
    }

    @Test @DisplayName("정상 STOP이 완료된 뒤 늦은 Fatal은 녹음 크레딧을 보상하지 않는다")
    void normalStopBeforeFatalDoesNotCompensate() {
        // 테스트 목적:
        // 정상 제공을 끝낸 세션에 시스템 실패 보상이 잘못 지급되지 않는지 검증한다.
        // given
        recordings.updateStatus(userId, id, RecordingSessionStatus.COMPLETED);
        // when
        fatal.onFatal(signal());
        // then
        assertThat(state()).isEqualTo("COMPLETED"); assertThat(meetingState()).isEqualTo("COMPLETED");
        assertThat(balance()).isEqualTo(80); assertThat(restoreCount()).isZero();
        verify(sse, never()).broadcast(eq(meetingId), eq("RECORDING_FAILED"), any());
    }

    void race(Runnable first, Runnable second) throws Exception {
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); first.run(); return null; });
            var b = pool.submit(() -> { start.await(); second.run(); return null; });
            start.countDown(); a.get(10, TimeUnit.SECONDS); b.get(10, TimeUnit.SECONDS);
        }
    }
}
