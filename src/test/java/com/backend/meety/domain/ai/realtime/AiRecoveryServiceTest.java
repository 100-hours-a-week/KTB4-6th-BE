package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.backend.meety.domain.ai.event.*;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.owner.RecordingOwnerRepository;
import com.backend.meety.domain.recording.owner.RecordingOwnerService;
import com.backend.meety.domain.recording.realtime.*;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import com.backend.meety.domain.transcript.service.TranscriptService;
import com.backend.meety.domain.transcript.realtime.TranscriptSaveDispatcher;
import com.backend.meety.domain.transcript.realtime.TranscriptSaveProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;

class AiRecoveryServiceTest {
    final AiRecoveryRegistry contexts = new AiRecoveryRegistry();
    final AiLiveMeetingConnectionRegistry connections = new AiLiveMeetingConnectionRegistry(contexts);
    final AudioWebSocketRegistry audio = new AudioWebSocketRegistry();
    final AudioSequenceRepository checkpointRepository = mock(AudioSequenceRepository.class);
    final RecordingOwnerRepository ownerRepository = mock(RecordingOwnerRepository.class);
    final RecordingSessionRepository recordingRepository = mock(RecordingSessionRepository.class);
    final TranscriptSegmentRepository transcriptRepository = mock(TranscriptSegmentRepository.class);
    final TranscriptService transcriptService = mock(TranscriptService.class);
    final RecordingSession recording = mock(RecordingSession.class);
    final ObjectMapper mapper = new ObjectMapper();
    final TestClock clock = new TestClock();
    final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    final ConcurrentLinkedQueue<Runnable> work = new ConcurrentLinkedQueue<>();
    final ConcurrentLinkedQueue<Runnable> transcriptWork = new ConcurrentLinkedQueue<>();
    final TranscriptSaveDispatcher transcriptSaveDispatcher = new TranscriptSaveDispatcher(transcriptWork::add,
            new TranscriptSaveProperties(500, 5000, Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ZERO), transcriptService);
    final List<Object> events = new CopyOnWriteArrayList<>();
    final List<String> feMessages = new CopyOnWriteArrayList<>();
    final List<String> starts = new CopyOnWriteArrayList<>();
    final List<WebSocketSession> aiSockets = new CopyOnWriteArrayList<>();
    final List<AiLiveMeetingInboundHandler> inbound = new CopyOnWriteArrayList<>();
    final AtomicLong checkpoint = new AtomicLong(99);
    final AtomicReference<AudioSequenceRepository> checkpointBackend = new AtomicReference<>();
    final AtomicReference<String> owner = new AtomicReference<>("owner-a");
    final AtomicReference<RecordingSessionStatus> status = new AtomicReference<>(RecordingSessionStatus.RECORDING);
    final AtomicLong transcriptMax = new AtomicLong(11);
    final Timers timers = new Timers();
    final WebSocketSession fe = mock(WebSocketSession.class);
    final WebSocketSession originalSocket = mock(WebSocketSession.class);
    final AiLiveMeetingConnection original = new AiLiveMeetingConnection(88L, 42L, "original");
    final AiAudioAckService ack = new AiAudioAckService(connections, audio, checkpointRepository,
            new AiAudioAckProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), 1024), clock, metrics, this::publish);
    final AiHeartbeatService heartbeat = new AiHeartbeatService(connections,
            new AiHeartbeatProperties(Duration.ofSeconds(5), Duration.ofSeconds(5), 2), new AiRequestIdGenerator(),
            mapper, clock, metrics, this::publish);
    final AiLiveMeetingConnectionService connectionService = new AiLiveMeetingConnectionService(
            connections, this::connect, new AiLiveMeetingProperties(URI.create("ws://localhost/ai")),
            new AiRequestIdGenerator(), timers, mapper, this::publish, transcriptSaveDispatcher,
            mock(RecordingOwnerService.class), mock(MeetingParticipantRepository.class), ack, heartbeat, contexts);
    final AiRecoveryService recovery = new AiRecoveryService(contexts, connections, connectionService, ownerRepository,
            recordingRepository, transcriptRepository, checkpointRepository, audio, heartbeat, timers, work::add,
            new AiRecoveryProperties(2, Duration.ofSeconds(5), 10, 2, 64), mapper, clock, metrics, this::publish);
    boolean autoReady = true;
    boolean autoPong = true;
    int failConnect;
    int failStart;
    CountDownLatch connectEntered;
    CountDownLatch connectRelease;

    AiRecoveryServiceTest() throws Exception {
        when(originalSocket.isOpen()).thenReturn(true);
        when(fe.isOpen()).thenReturn(true);
        original.attach(originalSocket);
        original.assignOwner("owner-a");
        original.markReady();
        connections.reserve(original);
        audio.replace(88L, fe);
        when(ownerRepository.find(88L)).thenAnswer(call -> Optional.ofNullable(owner.get()));
        when(recordingRepository.findByIdAndDeletedAtIsNull(88L)).thenReturn(Optional.of(recording));
        when(recording.getStatus()).thenAnswer(call -> status.get());
        when(transcriptRepository.findMaxSequenceNumber(42L)).thenAnswer(call -> transcriptMax.get());
        when(checkpointRepository.find(88L)).thenAnswer(call -> checkpointBackend.get() == null
                ? OptionalLong.of(checkpoint.get()) : checkpointBackend.get().find(88L));
        when(checkpointRepository.saveIfOwner(eq(88L), eq("owner-a"), anyLong())).thenAnswer(call -> {
            if (checkpointBackend.get() != null) { return checkpointBackend.get().saveIfOwner(88L, "owner-a", call.getArgument(2)); }
            checkpoint.accumulateAndGet(call.getArgument(2), Math::max); return true;
        });
        doAnswer(call -> { feMessages.add(((TextMessage) call.getArgument(0)).getPayload()); return null; })
                .when(fe).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("ACK timeout은 Owner-local Recovery를 한 번 시작하고 새 session.start와 replay를 요청한다")
    void ackTimeoutStartsRecovery() throws Exception {
        // 테스트 목적:
        // 안전 FE checkpoint 다음 값과 기존 전사 다음 값을 새 연결에 전달하는지 검증한다.
        // given
        ack.forwardAudio(original, mapper, 100, new byte[]{1});
        // when
        recovery.onAckTimeout(new AiAudioAckTimeoutEvent(88L, "original", 0, -1, clock.instant()));
        runAll();
        // then
        assertThat(metrics.counter("ai.recovery.started").count()).isEqualTo(1);
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(1);
        var start = mapper.readTree(starts.getFirst());
        assertThat(start.path("startSequence").asLong()).isEqualTo(100);
        assertThat(start.path("transcriptSequenceStart").asLong()).isEqualTo(12);
        assertThat(start.path("meetingId").asLong()).isEqualTo(42);
        assertThat(feMessages).anyMatch(text -> text.contains("recording.recovering"))
                .anyMatch(text -> text.contains("recording.replay.request") && text.contains("100"));
        assertThat(original.isClosed()).isTrue();
    }

    @Test
    @DisplayName("Heartbeat timeout은 Audio가 없어도 Recovery를 시작한다")
    void heartbeatTimeoutStartsRecovery() {
        // 테스트 목적:
        // 무음 구간의 서비스 장애도 같은 Recovery 진입점을 사용하는지 검증한다.
        // given
        heartbeat.tick();
        clock.advance(5);
        heartbeat.tick();
        // when
        clock.advance(5);
        heartbeat.tick();
        runAll();
        // then
        assertThat(contexts.count()).isEqualTo(1);
        assertThat(starts).hasSize(1);
    }

    @Test
    @DisplayName("같은 이전 연결의 ACK와 Heartbeat timeout 경쟁은 Recovery를 중복 시작하지 않는다")
    void concurrentTimeoutsStartOnce() throws Exception {
        // 테스트 목적:
        // 한 장애가 여러 감시 신호를 만들어도 첫 attempt를 이중 실행하거나 즉시 실패시키지 않는지 검증한다.
        // given
        ack.forwardAudio(original, mapper, 100, new byte[]{1});
        var hp = new AiHeartbeatProperties(Duration.ofSeconds(5), Duration.ofSeconds(5), 2);
        for (int i = 0; i < 3; i++) {
            original.heartbeatTick(mapper, hp, clock, () -> UUID.randomUUID().toString(), () -> true);
            if (i < 2) { clock.advance(5); }
        }
        // when
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var a = pool.submit(() -> { start.await(); recovery.onAckTimeout(new AiAudioAckTimeoutEvent(88L, "original", 0, -1, clock.instant())); return null; });
            var b = pool.submit(() -> { start.await(); recovery.onHeartbeatTimeout(new AiHeartbeatTimeoutEvent(88L, "original", "old", 2, clock.instant())); return null; });
            start.countDown(); a.get(2, TimeUnit.SECONDS); b.get(2, TimeUnit.SECONDS);
        }
        runAll();
        // then
        assertThat(metrics.counter("ai.recovery.started").count()).isEqualTo(1);
        assertThat(metrics.counter("ai.recovery.attempts").count()).isEqualTo(1);
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(1);
    }

    @Test
    @DisplayName("ready와 정상 pong 및 10청크 분량 누적 ACK가 모두 확인되면 Recovery를 완료한다")
    void tenChunksCompleteRecovery() throws Exception {
        // 테스트 목적:
        // ACK 메시지 개수가 아닌 실제 sequence 진행과 durable checkpoint를 기준으로 성공하는지 검증한다.
        // given
        begin(); runAll();
        var context = contexts.find(88L).orElseThrow();
        var timer = context.timeout;
        // when
        sendAudio(100, 109);
        aiAck(109);
        runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(checkpoint.get()).isEqualTo(109);
        assertThat(metrics.counter("ai.recovery.success").count()).isEqualTo(1);
        assertThat(timer.isCancelled()).isTrue();
        assertThat(feMessages).contains("{\"type\":\"ack\",\"seq\":109}")
                .contains("{\"type\":\"recording.recovery.completed\",\"recordingSessionId\":88}");
        clock.advance(10); timers.fireAll(); runAll();
        assertThat(starts).hasSize(1);
    }

    @Test
    @DisplayName("reconnect와 pong만 성공하고 ACK 진행이 없으면 attempt timeout으로 재시도한다")
    void noAckProgressTimesOut() {
        // 테스트 목적:
        // 연결이 만들어졌다는 이유만으로 Recovery를 성공 처리하거나 attempt를 초기화하지 않는지 검증한다.
        // given
        begin(); runAll();
        String generation = contexts.find(88L).orElseThrow().generation;
        // when
        clock.advance(5); timers.fireAll(); runAll();
        // then
        var context = contexts.find(88L).orElseThrow();
        assertThat(context.attempt).isEqualTo(2);
        assertThat(context.generation).isEqualTo(generation);
        assertThat(metrics.counter("ai.recovery.timeouts").count()).isEqualTo(1);
        assertThat(feMessages).noneMatch(text -> text.contains("recovery.completed"));
    }

    @Test
    @DisplayName("두 attempt가 실패하면 Fatal 진입 이벤트를 한 번 발행하고 상태와 timer를 정리한다")
    void exhaustedConnectFailures() {
        // 테스트 목적:
        // 재시도 한도를 지키고 #317 진입점 외의 Recording FAILED 처리를 중복 구현하지 않는지 검증한다.
        // given
        failConnect = 2;
        // when
        begin(); runAll();
        // then
        assertThat(metrics.counter("ai.recovery.attempts").count()).isEqualTo(2);
        assertThat(metrics.counter("ai.recovery.exhausted").count()).isEqualTo(1);
        assertThat(events.stream().filter(AiRecoveryExhaustedEvent.class::isInstance)).hasSize(1);
        assertThat(contexts.count()).isZero();
        assertThat(connections.count()).isZero();
        assertThat(timers.tasks).allMatch(task -> task.isCancelled());
        verify(recordingRepository, never()).save(any());
    }

    @Test
    @DisplayName("session.start write 실패도 attempt를 소모하고 두 번 실패하면 exhausted로 연결한다")
    void sessionStartFailureConsumesAttempts() {
        // 테스트 목적:
        // connect 이후 시작 명령 전송 실패가 Context를 초기화하거나 무한 재시도하지 않는지 검증한다.
        // given
        failStart = 2;
        // when
        begin(); runAll();
        // then
        assertThat(metrics.counter("ai.recovery.attempts").count()).isEqualTo(2);
        assertThat(contexts.count()).isZero();
        assertThat(events.stream().filter(AiRecoveryExhaustedEvent.class::isInstance)).hasSize(1);
    }

    @Test
    @DisplayName("두 attempt 모두 ACK 진행이 없으면 timeout 두 번 후 Fatal 진입점으로 연결한다")
    void twoAttemptDeadlinesExhaustRecovery() {
        // 테스트 목적:
        // 정상화 조건을 충족하지 못한 연결을 반복해서 무한 생성하지 않는지 검증한다.
        // given
        begin(); runAll();
        // when
        clock.advance(5); timers.fireAll(); runAll();
        clock.advance(5); timers.fireAll(); runAll();
        // then
        assertThat(metrics.counter("ai.recovery.timeouts").count()).isEqualTo(2);
        assertThat(contexts.count()).isZero();
        assertThat(events.stream().filter(AiRecoveryExhaustedEvent.class::isInstance)).hasSize(1);
    }

    @Test
    @DisplayName("성공 후에는 기존 일반 Audio 순번 gap 처리 계약을 유지한다")
    void completedRecoveryRestoresNormalForwardPolicy() throws Exception {
        // 테스트 목적:
        // 정상화 검증용 연속 replay 제한이 이후 일반 녹음의 기존 동작을 바꾸지 않는지 검증한다.
        // given
        begin(); runAll(); sendAudio(100, 109); aiAck(109); runAll();
        // when
        boolean sent = connectionService.forwardAudio(88L, 115, new byte[]{1});
        aiAck(110); runAll();
        // then
        assertThat(sent).isTrue();
        assertThat(checkpoint.get()).isEqualTo(115);
        assertThat(contexts.count()).isZero();
    }

    @Test
    @DisplayName("실제 Audio binary write 실패가 공통 Recovery 이벤트로 전달된다")
    void forwardWriteFailureEntersRecovery() throws Exception {
        // 테스트 목적:
        // 연결 I/O 실패를 단순 false로만 반환하고 유실하지 않는지 검증한다.
        // given
        doThrow(new java.io.IOException("AI socket failed")).when(originalSocket).sendMessage(any(BinaryMessage.class));
        // when
        boolean sent = connectionService.forwardAudio(88L, 100, new byte[]{1});
        runAll();
        // then
        assertThat(sent).isFalse();
        assertThat(starts).hasSize(1);
        assertThat(contexts.find(88L).orElseThrow().reason).isEqualTo("AUDIO_FORWARD_FAILURE");
    }

    @Test
    @DisplayName("STOP과 이전 failure 이벤트가 경쟁해도 취소 후 새 attempt가 생성되지 않는다")
    void stopRacesWithFailureEntry() throws Exception {
        // 테스트 목적:
        // Context 등록과 취소 사이의 빈틈에서 Recovery가 재생성되지 않는지 검증한다.
        // given
        var start = new CountDownLatch(1);
        // when
        try (var pool = Executors.newFixedThreadPool(2)) {
            var a = pool.submit(() -> { start.await(); begin(); return null; });
            var b = pool.submit(() -> { start.await(); connectionService.stop(88L); return null; });
            start.countDown(); a.get(3, TimeUnit.SECONDS); b.get(3, TimeUnit.SECONDS);
        }
        runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(starts).isEmpty();
    }

    @Test
    @DisplayName("첫 attempt의 일부 ACK가 저장된 뒤 실패하면 다음 attempt는 최신 checkpoint 다음부터 시작한다")
    void secondAttemptUsesLatestCheckpoint() throws Exception {
        // 테스트 목적:
        // 재시도마다 안전 위치를 재조회해 이미 확인된 PCM을 불필요하게 다시 요청하지 않는지 검증한다.
        // given
        begin(); runAll();
        sendAudio(100, 104); aiAck(104); runAll();
        var first = connections.find(88L).orElseThrow();
        // when
        recovery.onFailure(new AiRecoverableFailureEvent(88L, first.sessionStartRequestId(), "AI_TRANSPORT_ERROR"));
        runAll();
        // then
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(2);
        assertThat(mapper.readTree(starts.getLast()).path("startSequence").asLong()).isEqualTo(105);
        assertThat(connections.find(88L).orElseThrow()).isNotSameAs(first);
    }

    @Test
    @DisplayName("중복·이전 ACK는 10청크 성공 기준에 포함하지 않고 replay gap도 전송하지 않는다")
    void duplicatesAndGapsCannotComplete() throws Exception {
        // 테스트 목적:
        // 동일 ACK 반복이나 순번 건너뛰기로 정상화된 것처럼 오인하지 않는지 검증한다.
        // given
        begin(); runAll(); sendAudio(100, 104);
        // when
        for (int i = 0; i < 20; i++) { aiAck(104); aiAck(103); }
        boolean gap = connectionService.forwardAudio(88L, 109, new byte[]{1});
        runAll();
        // then
        assertThat(gap).isFalse();
        assertThat(contexts.count()).isEqualTo(1);
        assertThat(metrics.counter("ai.recovery.success").count()).isZero();
    }

    @Test
    @DisplayName("이전 connection의 늦은 ACK·pong·timeout·close·transport는 현재 attempt에 영향을 주지 않는다")
    void oldConnectionEventsAreFenced() throws Exception {
        // 테스트 목적:
        // 교체 전 connection의 callback이 새 attempt의 실패 횟수나 데이터 상태를 바꾸지 않는지 검증한다.
        // given
        begin(); runAll();
        var current = connections.find(88L).orElseThrow();
        var oldInbound = new AiLiveMeetingInboundHandler(original, connectionService, mapper, transcriptSaveDispatcher);
        // when
        oldInbound.handleTextMessage(originalSocket, new TextMessage("{\"type\":\"audio.ack\",\"recordingSessionId\":\"88\",\"payload\":{\"sequence\":999}}"));
        oldInbound.handleTextMessage(originalSocket, new TextMessage("{\"type\":\"heartbeat.pong\",\"recordingSessionId\":\"88\",\"requestId\":\"old\"}"));
        recovery.onAckTimeout(new AiAudioAckTimeoutEvent(88L, "original", 99, -1, clock.instant()));
        oldInbound.afterConnectionClosed(originalSocket, CloseStatus.SERVER_ERROR);
        oldInbound.handleTransportError(originalSocket, new java.io.IOException("old"));
        runAll();
        // then
        assertThat(connections.find(88L)).containsSame(current);
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(1);
        assertThat(checkpoint.get()).isEqualTo(99);
    }

    @ParameterizedTest
    @ValueSource(strings = {"AI_UNEXPECTED_CLOSE", "AI_TRANSPORT_ERROR", "AUDIO_FORWARD_FAILURE"})
    @DisplayName("AI 연결 계열 장애는 동일한 Recovery 진입점을 사용한다")
    void commonFailureEntry(String reason) {
        // 테스트 목적:
        // 연결 장애 종류별로 별도 재시도 흐름이 생기지 않는지 검증한다.
        // given
        // when
        recovery.onFailure(new AiRecoverableFailureEvent(88L, "original", reason)); runAll();
        // then
        assertThat(starts).hasSize(1);
        assertThat(contexts.find(88L).orElseThrow().reason).isEqualTo(reason);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACK", "HEARTBEAT"})
    @DisplayName("Recovery 중 새 연결의 ACK·Heartbeat timeout은 현재 attempt 실패로 처리한다")
    void newAttemptTimeoutSignalIsNotIgnored(String signal) throws Exception {
        // 테스트 목적:
        // RECOVERING 중 재발한 신호를 중복 시작 방어와 혼동해 무시하지 않는지 검증한다.
        // given
        begin(); runAll(); sendAudio(100, 100);
        var current = connections.find(88L).orElseThrow();
        // when
        if (signal.equals("ACK")) {
            recovery.onAckTimeout(new AiAudioAckTimeoutEvent(88L, current.sessionStartRequestId(), 100, 99, clock.instant()));
        } else {
            autoPong = false;
            var policy = new AiHeartbeatProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), 2);
            for (int i = 0; i < 3; i++) {
                clock.advance(1);
                current.heartbeatTick(mapper, policy, clock, () -> UUID.randomUUID().toString(), () -> true);
            }
            recovery.onHeartbeatTimeout(new AiHeartbeatTimeoutEvent(88L, current.sessionStartRequestId(), "timeout", 2, clock.instant()));
        }
        runAll();
        // then
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(2);
    }

    @Test
    @DisplayName("Recovery 중 STOP은 attempt와 timer를 취소하고 새 연결을 더 만들지 않는다")
    void stopCancelsRecovery() {
        // 테스트 목적:
        // 사용자 종료를 Recovery보다 우선하고 기존 정상 STOP 흐름을 사용하는지 검증한다.
        // given
        begin(); runAll();
        var timer = contexts.find(88L).orElseThrow().timeout;
        // when
        connectionService.stop(88L);
        clock.advance(10); timers.fireAll(); runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(timer.isCancelled()).isTrue();
        assertThat(starts).hasSize(1);
        assertThat(feMessages).noneMatch(text -> text.contains("recovery.completed"));
    }

    @Test
    @DisplayName("대기 중 ACK timeout이 정상화되어도 함께 발생한 Heartbeat 실패는 유실되지 않는다")
    void coalescedSignalsPreserveUnresolvedFailure() throws Exception {
        // 테스트 목적:
        // 중복 작업을 합치면서 서로 다른 장애 원인까지 단순 무시하지 않는지 검증한다.
        // given
        begin(); runAll(); sendAudio(100, 100);
        var current = connections.find(88L).orElseThrow();
        recovery.onAckTimeout(new AiAudioAckTimeoutEvent(88L, current.sessionStartRequestId(), 100, 99, clock.instant()));
        autoPong = false;
        var policy = new AiHeartbeatProperties(Duration.ofSeconds(1), Duration.ofSeconds(1), 2);
        for (int i = 0; i < 3; i++) {
            clock.advance(1);
            current.heartbeatTick(mapper, policy, clock, () -> UUID.randomUUID().toString(), () -> true);
        }
        // when
        recovery.onHeartbeatTimeout(new AiHeartbeatTimeoutEvent(88L, current.sessionStartRequestId(), "timeout", 2, clock.instant()));
        aiAck(100); runAll();
        // then
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(2);
        assertThat(metrics.counter("ai.recovery.failure").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("PAUSED 중 Recovery는 임시 replay 이후 AI PAUSE를 복원하고 DB 상태를 바꾸지 않는다")
    void pausedRecordingStaysPaused() throws Exception {
        // 테스트 목적:
        // 기술 상태 복구를 사용자 녹음 재개로 취급하지 않는지 검증한다.
        // given
        status.set(RecordingSessionStatus.PAUSED);
        begin(); runAll();
        // when
        sendAudio(100, 109); aiAck(109); runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(connections.find(88L).orElseThrow().state()).isEqualTo(AiLiveMeetingConnectionState.PAUSED);
        assertThat(status.get()).isEqualTo(RecordingSessionStatus.PAUSED);
        verify(recording, never()).resume(any());
        verify(recording, never()).pause(any());
    }

    @Test
    @DisplayName("Recovery 중 사용자 PAUSE·RESUME은 희망 상태로 반영되어 replay를 막지 않는다")
    void pauseAndResumeDuringReplay() throws Exception {
        // 테스트 목적:
        // 사용자 제어와 기술 정상화 상태를 분리하면서 최신 사용자 의도를 유지하는지 검증한다.
        // given
        begin(); runAll();
        connectionService.pause(88L);
        // when
        connectionService.resume(88L);
        sendAudio(100, 109); aiAck(109); runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(connections.find(88L).orElseThrow().state()).isEqualTo(AiLiveMeetingConnectionState.READY);
    }

    @Test
    @DisplayName("connection cleanup은 Recovery Context와 timer를 제거한다")
    void cleanupCancelsTasks() {
        // 테스트 목적:
        // 외부 cleanup 이후 이전 예약 작업으로 연결이 다시 생성되지 않는지 검증한다.
        // given
        begin(); runAll();
        var timer = contexts.find(88L).orElseThrow().timeout;
        // when
        connectionService.closeConnection(88L);
        clock.advance(10); timers.fireAll(); runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(connections.count()).isZero();
        assertThat(timer.isCancelled()).isTrue();
        assertThat(starts).hasSize(1);
    }

    @Test
    @DisplayName("Registry에서 새 연결을 직접 제거해도 Recovery timer가 정리된다")
    void registryRemovalCancelsTasks() {
        // 테스트 목적:
        // lifecycle 제거 경로마다 Context 참조가 남지 않는지 검증한다.
        // given
        begin(); runAll();
        var current = connections.find(88L).orElseThrow();
        var timer = contexts.find(88L).orElseThrow().timeout;
        // when
        connections.remove(88L, current);
        clock.advance(5); timers.fireAll(); runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(timer.isCancelled()).isTrue();
        assertThat(starts).hasSize(1);
    }

    @Test
    @DisplayName("Redis checkpoint 저장 실패는 AI ACK만으로 Recovery 성공을 만들지 않는다")
    void durableCheckpointRequired() throws Exception {
        // 테스트 목적:
        // FE에 안전 위치를 보장하지 못한 상태로 복구 완료를 알리지 않는지 검증한다.
        // given
        when(checkpointRepository.saveIfOwner(anyLong(), anyString(), anyLong())).thenReturn(false);
        begin(); runAll(); sendAudio(100, 109);
        // when
        aiAck(109); runAll();
        clock.advance(5); timers.fireAll(); runAll();
        // then
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(2);
        assertThat(feMessages).noneMatch(text -> text.contains("recovery.completed"));
    }

    @Test
    @DisplayName("session.ready만 오고 pong이 없으면 ACK 10청크가 있어도 성공하지 않는다")
    void heartbeatIsRequired() throws Exception {
        // 테스트 목적:
        // Audio 진행만으로 서비스 liveness 성공 조건을 대체하지 않는지 검증한다.
        // given
        autoPong = false;
        begin(); runAll(); sendAudio(100, 109);
        // when
        aiAck(109); runAll();
        // then
        assertThat(contexts.count()).isEqualTo(1);
        clock.advance(5); timers.fireAll(); runAll();
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(2);
    }

    @Test
    @DisplayName("session.ready가 오지 않으면 attempt timeout이 연결을 교체한다")
    void readyTimeoutConsumesAttempt() {
        // 테스트 목적:
        // WebSocket connect만 성공한 상태를 정상화로 취급하지 않는지 검증한다.
        // given
        autoReady = false;
        begin(); runAll();
        // when
        clock.advance(5); timers.fireAll(); runAll();
        // then
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(2);
        assertThat(feMessages).noneMatch(text -> text.contains("replay.request"));
    }

    @Test
    @DisplayName("Owner 변경은 이전 BE Context를 폐기하고 새 attempt를 승계하지 않는다")
    void ownerChangeAbandonsContext() {
        // 테스트 목적:
        // 이전 Owner가 새 Owner의 녹음을 복구하거나 Fatal로 처리하지 않는지 검증한다.
        // given
        begin(); runAll();
        var current = connections.find(88L).orElseThrow();
        owner.set("owner-b");
        // when
        recovery.onProgress(new AiRecoveryProgressEvent(88L, current.sessionStartRequestId())); runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(events.stream().filter(AiRecoveryExhaustedEvent.class::isInstance)).isEmpty();
        assertThat(starts).hasSize(1);
    }

    @Test
    @DisplayName("같은 BE에서 Owner가 바뀌면 이전 timer를 취소하고 기존 FE handshake 복구를 허용한다")
    void localOwnerHandoffDoesNotInheritAttempt() {
        // 테스트 목적:
        // 동일 AI 연결을 재사용하는 FE 재접속에도 이전 Context가 새 Owner의 전송을 막지 않는지 검증한다.
        // given
        begin(); runAll();
        var timer = contexts.find(88L).orElseThrow().timeout;
        // when
        owner.set("owner-b");
        connections.assignOwner(88L, "owner-b");
        boolean sent = connectionService.forwardAudio(88L, 100, new byte[]{1});
        // then
        assertThat(contexts.count()).isZero();
        assertThat(timer.isCancelled()).isTrue();
        assertThat(sent).isTrue();
    }

    @Test
    @DisplayName("deadline 직전에 성공 조건을 만족하면 대기 중인 진행 작업보다 timer가 먼저 실행돼도 완료한다")
    void completedProgressWinsAtDeadline() throws Exception {
        // 테스트 목적:
        // worker 큐 순서 때문에 이미 정상화된 attempt를 잘못 재시도하지 않는지 검증한다.
        // given
        begin(); runAll(); sendAudio(100, 109);
        // when
        clock.advance(4);
        aiAck(109);
        clock.advance(1);
        timers.fireAll(); runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(starts).hasSize(1);
        assertThat(metrics.counter("ai.recovery.success").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("deadline 이전 정상화 증거는 이후 ACK가 도착해도 사라지지 않는다")
    void laterProgressDoesNotEraseTimelyProof() throws Exception {
        // 테스트 목적:
        // 정상화 작업이 큐에서 대기하는 동안 더 최신 ACK 시각이 덮어써져 오탐하지 않는지 검증한다.
        // given
        begin(); runAll(); sendAudio(100, 109);
        clock.advance(4); aiAck(109);
        // when
        clock.advance(2); sendAudio(110, 110); aiAck(110);
        timers.fireAll(); runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(starts).hasSize(1);
        assertThat(metrics.counter("ai.recovery.success").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("connect 진행 중 STOP과 경쟁해도 늦게 연결된 WebSocket을 닫고 session.start를 보내지 않는다")
    void stopDuringConnectFencesLateSocket() throws Exception {
        // 테스트 목적:
        // 취소 전에 시작된 connect가 늦게 끝나도 새 정상 connection으로 부활하지 않는지 검증한다.
        // given
        connectEntered = new CountDownLatch(1); connectRelease = new CountDownLatch(1);
        begin();
        try (var pool = Executors.newSingleThreadExecutor()) {
            // when
            var attempt = pool.submit(() -> work.remove().run());
            assertThat(connectEntered.await(2, TimeUnit.SECONDS)).isTrue();
            connectionService.stop(88L);
            connectRelease.countDown();
            attempt.get(3, TimeUnit.SECONDS);
        } finally { connectRelease.countDown(); }
        runAll();
        // then
        assertThat(contexts.count()).isZero();
        assertThat(starts).isEmpty();
        assertThat(connections.count()).isZero();
        verify(aiSockets.getFirst()).close(CloseStatus.NORMAL);
    }

    @Test
    @DisplayName("비동기 저장을 기다리지 않고 큐에 수락된 이전 전사 이후 순번으로 두 attempt를 시작한다")
    void transcriptBarrierPreventsSequenceReuse() throws Exception {
        // 테스트 목적:
        // DB에 아직 없는 큐 수락 순번을 재시도에서도 보존해 비동기 전사 저장과 충돌하지 않는지 검증한다.
        // given
        String json = """
                {"type":"transcript.committed","meetingId":42,"recordingSessionId":88,
                 "payload":{"sequenceNumber":12,"content":"queued","startedAtMs":0,
                            "endedAtMs":100,"recognizedAt":"2026-10-21T00:00:00Z"}}
                """;
        var message = AiTranscriptSegmentMessage.from(mapper.readTree(json));
        var handler = new AiLiveMeetingInboundHandler(original, connectionService, mapper, transcriptSaveDispatcher);
        handler.handleTextMessage(originalSocket, new TextMessage(json));
        verifyNoInteractions(transcriptService);
        // when
        begin(); runAll();
        clock.advance(5); timers.fireAll(); runAll();
        // then
        assertThat(mapper.readTree(starts.getFirst()).path("transcriptSequenceStart").asLong()).isEqualTo(13);
        assertThat(mapper.readTree(starts.getLast()).path("transcriptSequenceStart").asLong()).isEqualTo(13);
        assertThat(transcriptMax.get()).isEqualTo(11);
        verifyNoInteractions(transcriptService);
        // when
        transcriptWork.remove().run();
        // then
        verify(transcriptService).saveFinalSegment(message);
    }

    @Test
    @DisplayName("완료 통지와 새 연결 장애가 경쟁해도 장애 신호가 사라지지 않는다")
    void failureRacingWithCompletionStartsNewEpisode() throws Exception {
        // 테스트 목적:
        // 직전 Context 제거 직전에 들어온 새 장애를 단순 무시하지 않고 다음 Recovery로 연결하는지 검증한다.
        // given
        begin(); runAll(); sendAudio(100, 109);
        var current = connections.find(88L).orElseThrow();
        doAnswer(call -> {
            String text = ((TextMessage) call.getArgument(0)).getPayload();
            feMessages.add(text);
            if (text.contains("recording.recovery.completed")) {
                recovery.onFailure(new AiRecoverableFailureEvent(88L, current.sessionStartRequestId(), "AI_TRANSPORT_ERROR"));
            }
            return null;
        }).when(fe).sendMessage(any(TextMessage.class));
        // when
        aiAck(109); runAll();
        // then
        assertThat(metrics.counter("ai.recovery.success").count()).isEqualTo(1);
        assertThat(metrics.counter("ai.recovery.started").count()).isEqualTo(2);
        assertThat(contexts.count()).isEqualTo(1);
        assertThat(starts).hasSize(2);
    }

    @Test
    @DisplayName("timeout 검사 중 native callback이 sendLock 안에서 재진입해도 Context 락 역전이 없다")
    void callbackUnderSendLockDoesNotDeadlockDeadline() throws Exception {
        // 테스트 목적:
        // transport callback과 timeout 검사가 서로의 락을 기다리며 멈추지 않는지 검증한다.
        // given
        begin(); runAll();
        var current = connections.find(88L).orElseThrow();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var writer = pool.submit(() -> current.enqueueTranscriptIfActive(12, () -> {
                entered.countDown();
                try { assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { throw new AssertionError(e); }
                connectionService.recoverableFailure(current, "AI_TRANSPORT_ERROR");
                return true;
            }));
            assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();
            // when
            clock.advance(5);
            var timeout = pool.submit(timers::fireAll);
            try { assertThatThrownBy(() -> timeout.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class); }
            finally { release.countDown(); }
            writer.get(3, TimeUnit.SECONDS); timeout.get(3, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        runAll();
        // then
        assertThat(contexts.find(88L).orElseThrow().attempt).isEqualTo(2);
    }

    void begin() { recovery.onFailure(new AiRecoverableFailureEvent(88L, "original", "AI_TRANSPORT_ERROR")); }
    void runAll() {
        for (int i = 0; i < 200; i++) { var task = work.poll(); if (task == null) { return; } task.run(); }
        throw new AssertionError("Unbounded recovery tasks");
    }
    void sendAudio(long first, long last) {
        for (long sequence = first; sequence <= last; sequence++) {
            assertThat(connectionService.forwardAudio(88L, sequence, new byte[]{1})).isTrue();
        }
    }
    void aiAck(long sequence) throws Exception {
        inbound.getLast().handleTextMessage(aiSockets.getLast(), new TextMessage(
                "{\"type\":\"audio.ack\",\"recordingSessionId\":\"88\",\"payload\":{\"sequence\":" + sequence + "}}"));
    }
    void publish(Object event) {
        events.add(event);
        if (event instanceof AiRecoverableFailureEvent failure) { recovery.onFailure(failure); }
        if (event instanceof AiAudioAckTimeoutEvent timeout) { recovery.onAckTimeout(timeout); }
        if (event instanceof AiHeartbeatTimeoutEvent timeout) { recovery.onHeartbeatTimeout(timeout); }
        if (event instanceof AiRecoveryProgressEvent progress) { recovery.onProgress(progress); }
    }
    WebSocketSession connect(WebSocketHandler handler, URI uri) {
        if (failConnect > 0) { failConnect--; throw new IllegalStateException("AI unavailable"); }
        var session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        var receiver = (AiLiveMeetingInboundHandler) handler;
        aiSockets.add(session); inbound.add(receiver);
        try {
            doAnswer(call -> {
                String text = ((TextMessage) call.getArgument(0)).getPayload();
                var node = mapper.readTree(text);
                String type = node.path("type").asText();
                if (type.equals("session.start")) {
                    if (failStart > 0) { failStart--; throw new java.io.IOException("start write failed"); }
                    starts.add(text);
                    if (autoReady) { receiver.handleTextMessage(session, new TextMessage(
                            "{\"type\":\"session.ready\",\"meetingId\":42,\"recordingSessionId\":88,\"requestId\":\""
                                    + node.path("requestId").asText() + "\",\"payload\":{\"status\":\"READY\"}}")); }
                } else if (type.equals("heartbeat.ping") && autoPong) {
                    work.add(() -> {
                        try { receiver.handleTextMessage(session, new TextMessage("{\"type\":\"heartbeat.pong\",\"recordingSessionId\":88,\"requestId\":\""
                                + node.path("requestId").asText() + "\"}")); }
                        catch (Exception e) { throw new AssertionError(e); }
                    });
                } else if (type.equals("session.pause")) {
                    receiver.handleTextMessage(session, new TextMessage("{\"type\":\"session.paused\",\"requestId\":\""
                            + node.path("requestId").asText() + "\",\"payload\":{\"status\":\"PAUSED\"}}"));
                }
                return null;
            }).when(session).sendMessage(any(TextMessage.class));
            if (connectEntered != null) {
                connectEntered.countDown();
                assertThat(connectRelease.await(3, TimeUnit.SECONDS)).isTrue();
            }
        } catch (Exception e) { throw new AssertionError(e); }
        return session;
    }
    static class TestClock extends Clock {
        volatile Instant now = Instant.parse("2026-10-21T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    static class Timers implements AiStopTimeoutScheduler {
        final List<Task> tasks = new CopyOnWriteArrayList<>();
        @Override public ScheduledFuture<?> schedule(Runnable action, Duration duration) {
            var task = new Task(action); tasks.add(task); return task;
        }
        void fireAll() { List.copyOf(tasks).forEach(task -> { if (!task.cancelled) { task.action.run(); } }); }
    }
    static class Task implements ScheduledFuture<Object> {
        final Runnable action; volatile boolean cancelled;
        Task(Runnable action) { this.action = action; }
        @Override public boolean cancel(boolean interrupt) { cancelled = true; return true; }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public boolean isDone() { return cancelled; }
        @Override public Object get() { return null; }
        @Override public Object get(long timeout, TimeUnit unit) { return null; }
        @Override public long getDelay(TimeUnit unit) { return 0; }
        @Override public int compareTo(Delayed other) { return 0; }
    }
}
