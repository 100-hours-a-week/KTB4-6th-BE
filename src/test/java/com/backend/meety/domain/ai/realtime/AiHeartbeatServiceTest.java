package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.backend.meety.domain.ai.event.AiAudioAckTimeoutEvent;
import com.backend.meety.domain.ai.event.AiHeartbeatTimeoutEvent;
import com.backend.meety.domain.recording.realtime.AudioSequenceRepository;
import com.backend.meety.domain.recording.realtime.AudioWebSocketRegistry;
import com.backend.meety.domain.transcript.service.TranscriptService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class AiHeartbeatServiceTest {
    private final AiLiveMeetingConnectionRegistry connections = new AiLiveMeetingConnectionRegistry();
    private final AiHeartbeatProperties properties = new AiHeartbeatProperties(
            Duration.ofSeconds(5), Duration.ofSeconds(5), 2);
    private final MutableClock clock = new MutableClock();
    private final ObjectMapper mapper = new ObjectMapper();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final AiHeartbeatService service = new AiHeartbeatService(connections, properties,
            new AiRequestIdGenerator(), mapper, clock, metrics, events::add);
    private final WebSocketSession socket = mock(WebSocketSession.class);
    private final AiLiveMeetingConnection connection = new AiLiveMeetingConnection(88L, 42L, "start-a");

    AiHeartbeatServiceTest() {
        when(socket.isOpen()).thenReturn(true);
        connection.attach(socket);
        connection.markReady();
        connections.reserve(connection);
    }

    @Test
    @DisplayName("READY 연결은 계약에 맞는 ping을 보내고 동일 requestId의 pong으로 정상 확인된다")
    void normalPingPong() throws Exception {
        // 테스트 목적:
        // 무음 상태에서도 Audio ACK와 무관하게 현재 연결의 응답 가능 여부를 확인하는지 검증한다.
        // given
        var messages = org.mockito.ArgumentCaptor.forClass(TextMessage.class);
        // when
        service.tick();
        String requestId = pingId();
        clock.advance(1);
        pong(connection, requestId);
        // then
        verify(socket).sendMessage(messages.capture());
        var ping = mapper.readTree(messages.getValue().getPayload());
        assertThat(ping.path("type").asText()).isEqualTo("heartbeat.ping");
        assertThat(ping.path("recordingSessionId").asText()).isEqualTo("88");
        assertThat(requestId).startsWith("hb-");
        assertThat(ping.properties()).hasSize(3);
        assertThat(connection.heartbeatSnapshot().lastPingRequestId()).isNull();
        assertThat(connection.heartbeatSnapshot().lastPongReceivedAt()).isEqualTo(clock.instant());
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isZero();
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isEqualTo(1);
        assertThat(metrics.counter("ai.heartbeat.pongs").count()).isEqualTo(1);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("빠른 pong을 받아도 ping interval 전에는 다음 ping을 보내지 않는다")
    void intervalIsRespected() {
        // 테스트 목적:
        // 응답 속도와 무관하게 설정한 probe 주기를 유지하는지 검증한다.
        // given
        service.tick();
        pong(connection, pingId());
        // when
        clock.advance(4);
        service.tick();
        // then
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isEqualTo(1);
        // when
        clock.advance(1);
        service.tick();
        // then
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("ping interval이 pong timeout보다 짧아도 outstanding ping은 하나만 유지한다")
    void onlyOneOutstandingPing() {
        // 테스트 목적:
        // 응답 대기 중인 requestId를 주기마다 덮어써 정상 pong을 유실하지 않는지 검증한다.
        // given
        var fast = new AiHeartbeatService(connections,
                new AiHeartbeatProperties(Duration.ofSeconds(1), Duration.ofSeconds(5), 2),
                new AiRequestIdGenerator(), mapper, clock, metrics, events::add);
        fast.tick();
        String first = pingId();
        // when
        for (int i = 0; i < 4; i++) { clock.advance(1); fast.tick(); }
        // then
        assertThat(pingId()).isEqualTo(first);
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isEqualTo(1);
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isZero();
    }

    @Test
    @DisplayName("첫 pong timeout은 실패 1회만 기록하고 종료나 최종 장애 이벤트를 발생시키지 않는다")
    void firstTimeoutIsNotFatal() throws Exception {
        // 테스트 목적:
        // 단일 지연으로 회의나 WebSocket을 종료하지 않고 다음 probe를 시도하는지 검증한다.
        // given
        service.tick();
        String first = pingId();
        // when
        clock.advance(5);
        service.tick();
        service.tick();
        // then
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isEqualTo(1);
        assertThat(pingId()).isNotEqualTo(first);
        assertThat(metrics.counter("ai.heartbeat.timeouts").count()).isEqualTo(1);
        assertThat(events).isEmpty();
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.READY);
        verify(socket, never()).close(any());
    }

    @Test
    @DisplayName("연속 두 번 timeout이면 장애 이벤트를 한 번만 발행하고 이후 probe는 유지한다")
    void thresholdOnlyOncePerFailureEpisode() {
        // 테스트 목적:
        // 후속 Recovery 진입점은 제공하되 같은 장애 구간의 중복 이벤트를 방지하는지 검증한다.
        // given
        service.tick();
        // when
        for (int i = 0; i < 4; i++) { clock.advance(5); service.tick(); }
        // then
        assertThat(events).hasSize(1);
        var event = (AiHeartbeatTimeoutEvent) events.getFirst();
        assertThat(event.reason()).isEqualTo("AI_HEARTBEAT_TIMEOUT");
        assertThat(event.recordingSessionId()).isEqualTo(88L);
        assertThat(event.sessionStartRequestId()).isEqualTo("start-a");
        assertThat(event.consecutiveFailures()).isEqualTo(2);
        assertThat(event.pingRequestId()).startsWith("hb-");
        assertThat(metrics.counter("ai.heartbeat.threshold.reached").count()).isEqualTo(1);
        assertThat(metrics.counter("ai.heartbeat.timeouts").count()).isEqualTo(4);
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isEqualTo(5);
    }

    @Test
    @DisplayName("정상 pong은 연속 실패와 이벤트 latch를 초기화하고 새 장애를 다시 감지한다")
    void pongResetsAndRearmsFailures() {
        // 테스트 목적:
        // 응답이 회복된 뒤의 실패는 이전 장애와 독립적으로 계산하는지 검증한다.
        // given
        service.tick();
        clock.advance(5);
        service.tick();
        // when
        pong(connection, pingId());
        // then
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isZero();
        assertThat(connection.heartbeatSnapshot().timeoutReported()).isFalse();
        // when
        for (int i = 0; i < 3; i++) { clock.advance(5); service.tick(); }
        pong(connection, pingId());
        for (int i = 0; i < 3; i++) { clock.advance(5); service.tick(); }
        // then
        assertThat(events).hasSize(2);
    }

    @Test
    @DisplayName("PAUSED에서도 신규 Audio 없이 ping과 pong을 유지한다")
    void pausedIsLive() throws Exception {
        // 테스트 목적:
        // 사용자 일시정지가 AI liveness 감시를 중단하지 않는지 검증한다.
        // given
        connection.sendPause(mapper, "pause-a");
        connection.markPaused();
        // when
        service.tick();
        pong(connection, pingId());
        // then
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.PAUSED);
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isEqualTo(1);
        assertThat(metrics.counter("ai.heartbeat.pongs").count()).isEqualTo(1);
        assertThat(connection.ackSnapshot().lag()).isZero();
    }

    @ParameterizedTest
    @EnumSource(value = AiLiveMeetingConnectionState.class, names = {
            "CONNECTING", "START_SENT", "PAUSE_SENT", "RESUME_SENT", "STOP_SENT", "ENDED", "CLOSED"})
    @DisplayName("활성 안정 상태가 아닌 연결에는 새로운 Heartbeat ping을 보내지 않는다")
    void noNewPingOutsideReadyAndPaused(AiLiveMeetingConnectionState target) throws Exception {
        // 테스트 목적:
        // 연결 준비·제어 전환·종료 중에 신규 liveness probe를 시작하지 않는지 검증한다.
        // given
        moveTo(target);
        // when
        service.tick();
        // then
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isZero();
        assertThat(connection.heartbeatSnapshot().lastPingRequestId()).isNull();
    }

    @Test
    @DisplayName("PAUSE 전환 중에도 이미 보낸 ping의 pong은 수신할 수 있다")
    void outstandingPongSurvivesPauseTransition() throws Exception {
        // 테스트 목적:
        // 제어 명령 전환만으로 유효한 outstanding 응답을 무시하지 않는지 검증한다.
        // given
        service.tick();
        String id = pingId();
        connection.sendPause(mapper, "pause-a");
        // when
        pong(connection, id);
        // then
        assertThat(metrics.counter("ai.heartbeat.pongs").count()).isEqualTo(1);
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{}",
            "{\"type\":\"heartbeat.pong\",\"recordingSessionId\":\"99\",\"requestId\":\"ID\"}",
            "{\"type\":\"heartbeat.pong\",\"recordingSessionId\":\"88\"}",
            "{\"type\":\"heartbeat.pong\",\"recordingSessionId\":\"88\",\"requestId\":101}",
            "{\"type\":\"heartbeat.pong\",\"recordingSessionId\":\"88\",\"requestId\":\" \"}",
            "{\"type\":\"audio.ack\",\"recordingSessionId\":\"88\",\"requestId\":\"ID\"}"
    })
    @DisplayName("세션 불일치·필수값 누락·잘못된 타입의 pong은 실패 상태를 정상화하지 않는다")
    void malformedPongIsIgnored(String json) {
        // 테스트 목적:
        // 잘못된 외부 메시지로 현재 연결의 실패 횟수나 outstanding 상태가 바뀌지 않는지 검증한다.
        // given
        service.tick();
        clock.advance(5);
        service.tick();
        var before = connection.heartbeatSnapshot();
        // when
        service.receivePong(connection, mapper.readTree(json.replace("ID", pingId())));
        // then
        assertThat(connection.heartbeatSnapshot()).isEqualTo(before);
        assertThat(metrics.counter("ai.heartbeat.pongs.invalid").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 ping·알 수 없는 requestId·중복 pong은 현재 실패 횟수를 초기화하지 않는다")
    void staleUnknownAndDuplicatePong() {
        // 테스트 목적:
        // 이전 probe 응답을 최신 연결의 정상 응답으로 오인하지 않는지 검증한다.
        // given
        service.tick();
        String first = pingId();
        pong(connection, first);
        pong(connection, first);
        clock.advance(5);
        service.tick();
        clock.advance(5);
        service.tick();
        var before = connection.heartbeatSnapshot();
        // when
        pong(connection, first);
        pong(connection, "hb-unknown");
        // then
        assertThat(connection.heartbeatSnapshot()).isEqualTo(before);
        assertThat(before.consecutiveHeartbeatFailures()).isEqualTo(1);
        assertThat(metrics.counter("ai.heartbeat.pongs.invalid").count()).isEqualTo(3);
    }

    @Test
    @DisplayName("deadline에 도달한 늦은 pong은 scheduler 실행 전에도 정상화하지 않는다")
    void expiredPongCannotHideTimeout() {
        // 테스트 목적:
        // scheduler 지연으로 만료된 probe가 성공으로 바뀌지 않도록 경계 정책을 고정한다.
        // given
        service.tick();
        String id = pingId();
        // when
        clock.advance(5);
        pong(connection, id);
        service.tick();
        // then
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isEqualTo(1);
        assertThat(metrics.counter("ai.heartbeat.pongs").count()).isZero();
        assertThat(metrics.counter("ai.heartbeat.timeouts").count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"STOP", "MARK_STOP", "ENDED", "CLOSED", "REMOVE", "CLEANUP"})
    @DisplayName("종료·cleanup·Registry 제거 후 Heartbeat 상태가 정리되고 신규 ping이 없다")
    void terminalLifecycleClearsHeartbeat(String action) throws Exception {
        // 테스트 목적:
        // 종료된 세션을 scheduler가 계속 참조하거나 늦은 pong으로 상태가 부활하지 않는지 검증한다.
        // given
        service.tick();
        String id = pingId();
        clock.advance(5);
        service.tick();
        // when
        switch (action) {
            case "STOP" -> connection.sendStop(mapper, "stop-a");
            case "MARK_STOP" -> connection.markStopSent("stop-a");
            case "ENDED" -> { connection.markStopSent("stop-a"); connection.markEnded(); }
            case "CLOSED" -> connection.close();
            case "REMOVE" -> connections.remove(88L, connection);
            case "CLEANUP" -> new AiAudioAckService(connections, new AudioWebSocketRegistry(),
                    mock(AudioSequenceRepository.class),
                    new AiAudioAckProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), 1024),
                    clock, metrics, events::add).cleanup(connection);
            default -> throw new AssertionError(action);
        }
        clock.advance(30);
        service.tick();
        pong(connection, id);
        // then
        assertThat(connection.heartbeatSnapshot()).isEqualTo(
                new AiLiveMeetingConnection.HeartbeatSnapshot(null, null, null, 0, false));
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isEqualTo(2);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("ping write 실패는 outstanding을 만들지 않으며 다른 connection 검사는 계속된다")
    void sendFailureIsIsolated() throws Exception {
        // 테스트 목적:
        // 전송 실패를 정상 송신으로 기록하거나 한 연결의 예외로 전체 감시를 중단하지 않는지 검증한다.
        // given
        doThrow(new java.io.IOException("write failed")).when(socket).sendMessage(any(TextMessage.class));
        var other = new AiLiveMeetingConnection(99L, 42L, "start-b");
        var otherSocket = mock(WebSocketSession.class);
        when(otherSocket.isOpen()).thenReturn(true);
        other.attach(otherSocket);
        other.markReady();
        connections.reserve(other);
        // when
        service.tick();
        // then
        assertThat(connection.heartbeatSnapshot().lastPingRequestId()).isNull();
        assertThat(other.heartbeatSnapshot().lastPingRequestId()).isNotNull();
        assertThat(metrics.counter("ai.heartbeat.send.failures").count()).isEqualTo(1);
        assertThat(metrics.counter("ai.heartbeat.timeouts").count()).isZero();
    }

    @Test
    @DisplayName("STOP 뒤 늦은 ready 신호가 와도 Heartbeat를 다시 시작하지 않는다")
    void stoppedHeartbeatCannotBeResurrected() {
        // 테스트 목적:
        // 기존 제어 응답의 지연 도착이 종료된 연결의 liveness 작업을 부활시키지 않는지 검증한다.
        // given
        service.tick();
        connection.markStopSent("stop-a");
        // when
        connection.markReady();
        clock.advance(30);
        service.tick();
        // then
        assertThat(connection.heartbeatSnapshot().lastPingRequestId()).isNull();
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isEqualTo(1);
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("timeout 이벤트 consumer 예외가 다른 connection의 감시를 중단하지 않는다")
    void eventListenerFailureDoesNotStopScan() {
        // 테스트 목적:
        // 후속 Recovery consumer가 실패하더라도 공용 scheduler가 다른 연결을 계속 검사하는지 검증한다.
        // given
        var other = new AiLiveMeetingConnection(99L, 42L, "start-b");
        other.attach(socket);
        other.markReady();
        connections.reserve(other);
        var failingConsumer = new AiHeartbeatService(connections, properties, new AiRequestIdGenerator(),
                mapper, clock, metrics, event -> { throw new IllegalStateException("consumer failed"); });
        // when
        failingConsumer.tick();
        clock.advance(5);
        failingConsumer.tick();
        clock.advance(5);
        failingConsumer.tick();
        // then
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isEqualTo(2);
        assertThat(other.heartbeatSnapshot().consecutiveHeartbeatFailures()).isEqualTo(2);
        assertThat(metrics.counter("ai.heartbeat.threshold.reached").count()).isEqualTo(2);
        assertThat(metrics.counter("ai.heartbeat.timeouts").count()).isEqualTo(4);
    }

    @Test
    @DisplayName("ping write가 오래 걸려도 전송 완료 시점부터 pong timeout을 계산한다")
    void deadlineStartsAfterWriteCompletes() throws Exception {
        // 테스트 목적:
        // 실제 송신 완료 전에 대기 시간이 소진되어 즉시 timeout이 발생하지 않는지 검증한다.
        // given
        doAnswer(call -> { clock.advance(30); return null; }).when(socket).sendMessage(any(TextMessage.class));
        // when
        service.tick();
        service.tick();
        // then
        assertThat(connection.heartbeatSnapshot().lastPingSentAt()).isEqualTo(clock.instant());
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isZero();
        // when
        clock.advance(5);
        service.tick();
        // then
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("Audio ACK 정지는 pong 정상 여부와 무관하게 AUDIO_ACK_TIMEOUT으로만 감지된다")
    void heartbeatDoesNotTouchAudioAck() throws Exception {
        // 테스트 목적:
        // Heartbeat 성공이 Audio ACK timer나 Redis·FE checkpoint를 대신 전진시키지 않는지 검증한다.
        // given
        var audio = new AudioWebSocketRegistry();
        var redis = mock(AudioSequenceRepository.class);
        when(redis.saveIfOwner(eq(88L), isNull(), anyLong())).thenReturn(true);
        var fe = mock(WebSocketSession.class);
        audio.replace(88L, fe);
        var ack = new AiAudioAckService(connections, audio, redis,
                new AiAudioAckProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), 1024),
                clock, metrics, events::add);
        for (long i = 1; i <= 10; i++) { ack.forwardAudio(connection, mapper, i, new byte[]{1}); }
        var before = connection.ackSnapshot();
        service.tick();
        // when
        clock.advance(4);
        pong(connection, pingId());
        // then
        assertThat(connection.ackSnapshot()).isEqualTo(before);
        verifyNoInteractions(redis, fe);
        // when
        clock.advance(1);
        ack.detectTimeouts();
        service.tick();
        var heartbeat = connection.heartbeatSnapshot();
        ack.receive(connection, mapper.readTree("{\"recordingSessionId\":\"88\",\"payload\":{\"sequence\":9}}"), mapper);
        // then
        assertThat(events).hasSize(1).first().isInstanceOf(AiAudioAckTimeoutEvent.class);
        assertThat(connection.heartbeatSnapshot()).isEqualTo(heartbeat);
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(9);
        assertThat(connection.ackSnapshot().lastAckProgressAt()).isNull();
        verify(redis).saveIfOwner(88L, null, 10L);
        verify(fe).sendMessage(any(TextMessage.class));
    }

    @Test
    @DisplayName("정상 Audio ACK도 Heartbeat 연속 실패를 초기화하지 않는다")
    void audioAckDoesNotResetHeartbeat() throws Exception {
        // 테스트 목적:
        // PCM 진행 확인과 서비스 liveness 상태를 별도로 유지하는지 검증한다.
        // given
        service.tick();
        clock.advance(5);
        service.tick();
        var before = connection.heartbeatSnapshot();
        connection.forwardAudio(mapper, new byte[]{1}, 1, clock, 1024);
        // when
        connection.acknowledge(0, clock.instant(), (sequence, count) -> true);
        // then
        assertThat(connection.heartbeatSnapshot()).isEqualTo(before);
        assertThat(before.consecutiveHeartbeatFailures()).isEqualTo(1);
    }

    @Test
    @DisplayName("timeout 직전 pong과 검사 경쟁에서 정상 응답 후 실패가 증가하지 않는다")
    void validPongRacesWithTick() throws Exception {
        // 테스트 목적:
        // 같은 락에서 응답과 검사를 판정해 정상 응답 직후 오탐하지 않는지 검증한다.
        // given
        service.tick();
        String id = pingId();
        clock.advance(4);
        // when
        runTogether(() -> service.tick(), () -> pong(connection, id));
        clock.advance(1);
        service.tick();
        // then
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isZero();
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("deadline의 pong과 동시 timeout 검사들은 같은 ping 실패를 한 번만 집계한다")
    void concurrentTimeoutAndLatePongAreCountedOnce() throws Exception {
        // 테스트 목적:
        // 동일 probe를 여러 스레드가 만료시켜도 실패 횟수와 이벤트가 중복되지 않는지 검증한다.
        // given
        service.tick();
        clock.advance(5);
        service.tick();
        String id = pingId();
        clock.advance(5);
        // when
        runTogether(service::tick, service::tick, service::tick, () -> pong(connection, id));
        // then
        assertThat(connection.heartbeatSnapshot().consecutiveHeartbeatFailures()).isEqualTo(2);
        assertThat(events).hasSize(1);
        assertThat(metrics.counter("ai.heartbeat.timeouts").count()).isEqualTo(2);
    }

    @Test
    @DisplayName("ping write와 cleanup이 경쟁해도 cleanup 완료 뒤에는 신규 ping이 없다")
    void cleanupRacesWithPing() throws Exception {
        // 테스트 목적:
        // 기존 sendLock으로 write와 종료 경계를 직렬화하고 종료 상태가 다시 생기지 않는지 검증한다.
        // given
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        doAnswer(call -> { writing.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(socket).sendMessage(any(TextMessage.class));
        try (var pool = Executors.newFixedThreadPool(2)) {
            // when
            var tick = pool.submit(service::tick);
            assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue();
            var close = pool.submit(() -> connections.remove(88L, connection));
            try {
                // then
                assertThatThrownBy(() -> close.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            tick.get(3, TimeUnit.SECONDS);
            close.get(3, TimeUnit.SECONDS);
        }
        clock.advance(30);
        service.tick();
        assertThat(connection.isClosed()).isTrue();
        assertThat(connection.heartbeatSnapshot().lastPingRequestId()).isNull();
        assertThat(metrics.counter("ai.heartbeat.pings").count()).isEqualTo(1);
    }

    @Test
    @DisplayName("이전 connection의 늦은 pong은 새 connection의 연속 실패를 초기화하지 않는다")
    void oldConnectionPongCannotResetReplacement() throws Exception {
        // 테스트 목적:
        // 같은 recordingSessionId를 재사용해도 현재 registry 연결 세대로 pong을 fencing하는지 검증한다.
        // given
        service.tick();
        String oldId = pingId();
        var next = new AiLiveMeetingConnection(88L, 42L, "start-b");
        next.attach(socket);
        next.markReady();
        // when
        runTogether(() -> {
            connections.remove(88L, connection);
            assertThat(connections.reserve(next)).isTrue();
        }, () -> pong(connection, oldId));
        service.tick();
        clock.advance(5);
        service.tick();
        var before = next.heartbeatSnapshot();
        pong(connection, oldId);
        // then
        assertThat(next.heartbeatSnapshot()).isEqualTo(before);
        assertThat(before.consecutiveHeartbeatFailures()).isEqualTo(1);
        assertThat(connection.isClosed()).isTrue();
    }

    @Test
    @DisplayName("AI inbound heartbeat.pong은 Heartbeat 전용 서비스 경로로 전달된다")
    void inboundRoutesPong() throws Exception {
        // 테스트 목적:
        // AI 메시지 분기에서 pong을 ACK나 일반 메시지 처리와 혼동하지 않는지 검증한다.
        // given
        var connectionService = mock(AiLiveMeetingConnectionService.class);
        when(connectionService.isCurrent(connection)).thenReturn(true);
        var inbound = new AiLiveMeetingInboundHandler(connection, connectionService, mapper, mock(TranscriptService.class));
        String json = "{\"type\":\"heartbeat.pong\",\"requestId\":\"hb-a\",\"recordingSessionId\":\"88\"}";
        // when
        inbound.handleTextMessage(socket, new TextMessage(json));
        // then
        verify(connectionService).receiveHeartbeatPong(connection, mapper.readTree(json));
        verify(connectionService, never()).receiveAudioAck(any(), any());
        verify(connectionService, never()).cleanup(any());
    }

    private String pingId() { return connection.heartbeatSnapshot().lastPingRequestId(); }

    private void pong(AiLiveMeetingConnection target, String id) {
        service.receivePong(target, mapper.readTree("{\"type\":\"heartbeat.pong\",\"recordingSessionId\":\"88\",\"requestId\":\"" + id + "\"}"));
    }

    private void moveTo(AiLiveMeetingConnectionState target) throws Exception {
        switch (target) {
            case CONNECTING -> {
                connections.remove(88L, connection);
                connections.reserve(new AiLiveMeetingConnection(88L, 42L, "start-new"));
            }
            case START_SENT -> {
                connections.remove(88L, connection);
                var starting = new AiLiveMeetingConnection(88L, 42L, "start-new");
                starting.markStartSent();
                connections.reserve(starting);
            }
            case PAUSE_SENT -> connection.sendPause(mapper, "pause-a");
            case RESUME_SENT -> {
                connection.sendPause(mapper, "pause-a"); connection.markPaused(); connection.sendResume(mapper, "resume-a");
            }
            case STOP_SENT -> connection.markStopSent("stop-a");
            case ENDED -> { connection.markStopSent("stop-a"); connection.markEnded(); }
            case CLOSED -> connection.close();
            default -> throw new AssertionError(target);
        }
    }

    private void runTogether(Runnable... work) throws Exception {
        try (var pool = Executors.newFixedThreadPool(work.length)) {
            var start = new CountDownLatch(1);
            List<Future<?>> tasks = new ArrayList<>();
            for (var action : work) {
                tasks.add(pool.submit(() -> { assertThat(start.await(2, TimeUnit.SECONDS)).isTrue(); action.run(); return null; }));
            }
            start.countDown();
            for (var task : tasks) { task.get(3, TimeUnit.SECONDS); }
        }
    }

    private static class MutableClock extends Clock {
        private volatile Instant now = Instant.parse("2026-10-20T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
