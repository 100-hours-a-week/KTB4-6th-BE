package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.backend.meety.domain.ai.event.AiAudioAckTimeoutEvent;
import com.backend.meety.domain.recording.realtime.*;
import com.backend.meety.domain.transcript.realtime.TranscriptSaveDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.*;
import java.util.List;
import java.util.concurrent.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.socket.*;
import tools.jackson.databind.ObjectMapper;

class AiAudioAckServiceTest {
    private final AiLiveMeetingConnectionRegistry connections = new AiLiveMeetingConnectionRegistry();
    private final AudioWebSocketRegistry audioRegistry = new AudioWebSocketRegistry();
    private final AudioSequenceRepository repository = mock(AudioSequenceRepository.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final MutableClock clock = new MutableClock();
    private final SimpleMeterRegistry metrics = new SimpleMeterRegistry();
    private final List<Object> events = new CopyOnWriteArrayList<>();
    private final AiAudioAckService service = new AiAudioAckService(connections, audioRegistry, repository,
            new AiAudioAckProperties(Duration.ofSeconds(5), Duration.ofSeconds(1), 1024), clock, metrics, events::add);
    private final WebSocketSession aiSocket = mock(WebSocketSession.class);
    private final WebSocketSession feSocket = mock(WebSocketSession.class);
    private final AiLiveMeetingConnection connection = ready("connection-a");

    AiAudioAckServiceTest() {
        when(aiSocket.isOpen()).thenReturn(true);
        connection.attach(aiSocket);
        connections.reserve(connection);
        audioRegistry.replace(88L, feSocket);
        when(repository.saveIfOwner(eq(88L), isNull(), anyLong())).thenReturn(true);
    }

    @Test
    @DisplayName("AI write 성공만으로 checkpoint와 FE ACK는 전진하지 않는다")
    void writeDoesNotConfirmReception() throws Exception {
        // 테스트 목적:
        // AI의 수신 확인 전에는 FE 재전송 기준을 전진시키지 않는지 검증한다.
        // given
        // when
        sendThrough(10);
        // then
        assertThat(connection.ackSnapshot().lastSentSequence()).isEqualTo(9);
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(-1);
        assertThat(connection.ackSnapshot().lastAckReceivedAt()).isNull();
        verifyNoInteractions(repository, feSocket);
        assertThat(audioRegistry.streamState(88L).lastProcessedSequence()).isZero();
    }

    @Test
    @DisplayName("AI ACK를 FE 순번으로 대응시키고 Redis 성공 후 10청크 누적 ACK를 전송한다")
    void checkpointBeforeCumulativeAck() throws Exception {
        // 테스트 목적:
        // 서로 다른 AI/FE 순번을 혼동하지 않고 확인된 청크만 기존 형식으로 응답하는지 검증한다.
        // given
        sendThrough(10);
        // when
        for (int sequence = 0; sequence < 10; sequence++) { ack(sequence); }
        // then
        var order = inOrder(repository, feSocket);
        order.verify(repository).saveIfOwner(88L, null, 10L);
        ArgumentCaptor<WebSocketMessage<?>> message = ArgumentCaptor.forClass(WebSocketMessage.class);
        order.verify(feSocket).sendMessage(message.capture());
        assertThat(message.getValue().getPayload()).isEqualTo("{\"type\":\"ack\",\"seq\":10}");
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(9);
        assertThat(connection.ackSnapshot().lastAckProgressAt()).isNull();
        assertThat(connection.pendingAudioCount()).isZero();
        assertThat(metrics.get("ai.audio.ack.received").counter().count()).isEqualTo(10);
        assertThat(metrics.get("ai.audio.ack.lag").gauge().value()).isZero();
    }

    @Test
    @DisplayName("중복 및 역순 ACK는 sequence와 마지막 진행 시각을 역행시키지 않는다")
    void duplicatesAndStaleDoNotRegress() throws Exception {
        // 테스트 목적:
        // 늦은 ACK로 checkpoint가 역행하거나 timeout 기준이 연장되지 않는지 검증한다.
        // given
        sendThrough(103);
        ack(101);
        Instant progressed = connection.ackSnapshot().lastAckReceivedAt();
        // when
        clock.advance(4);
        ack(101);
        ack(100);
        // then
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(101);
        assertThat(connection.ackSnapshot().lastAckReceivedAt()).isEqualTo(progressed);
        verify(repository, times(1)).saveIfOwner(anyLong(), any(), anyLong());
        assertThat(metrics.get("ai.audio.ack.outcomes").tag("result", "duplicate").counter().count()).isOne();
        assertThat(metrics.get("ai.audio.ack.outcomes").tag("result", "stale").counter().count()).isOne();
        ack(102);
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(102);
        assertThat(connection.ackSnapshot().lastAckProgressAt()).isNull();
    }

    @Test
    @DisplayName("발급 순번·전송 완료 순번·AI 확인 순번을 구분하고 미래 ACK는 거절한다")
    void sentAndFutureAckHaveDistinctMeaning() throws Exception {
        // 테스트 목적:
        // 마지막 송신이 101일 때 미래 ACK 105로 sent나 ACK 위치를 임의로 전진시키지 않는지 검증한다.
        // given
        sendThrough(102);
        // when
        ack(105);
        // then
        assertThat(connection.ackSnapshot().nextAudioSequence()).isEqualTo(102);
        assertThat(connection.ackSnapshot().lastSentSequence()).isEqualTo(101);
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(-1);
        assertThat(metrics.get("ai.audio.ack.invalid").counter().count()).isOne();
        verifyNoInteractions(repository, feSocket);
        // when
        ack(101);
        // then
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(101);
        assertThat(connection.ackSnapshot().lastAckProgressAt()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"-1", "1.5", "\"9\"", "null", "9223372036854775808", "100"})
    @DisplayName("음수·비정수·범위 초과·미전송 순번 ACK는 무시한다")
    void invalidSequenceDoesNotAdvance(String value) throws Exception {
        // 테스트 목적:
        // 잘못된 AI 응답이 재전송 기준을 오염시키지 않는지 검증한다.
        // given
        sendThrough(10);
        // when
        service.receive(connection, mapper.readTree("{\"recordingSessionId\":\"88\",\"payload\":{\"sequence\":" + value + "}}"), mapper);
        // then
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(-1);
        verifyNoInteractions(repository, feSocket);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"recordingSessionId\":\"99\",\"payload\":{\"sequence\":0}}",
            "{\"recordingSessionId\":\"88\",\"payload\":{}}"})
    @DisplayName("세션 불일치와 필수값 누락 ACK는 현재 연결에 반영하지 않는다")
    void wrongSessionOrMissingFields(String json) throws Exception {
        // 테스트 목적:
        // 다른 녹음의 응답이나 불완전한 응답을 현재 녹음에 적용하지 않는지 검증한다.
        // given
        sendThrough(1);
        // when
        service.receive(connection, mapper.readTree(json), mapper);
        // then
        verifyNoInteractions(repository, feSocket);
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(-1);
    }

    @Test
    @DisplayName("Redis 실패 시 FE ACK를 억제하고 같은 AI ACK로 checkpoint를 재시도할 수 있다")
    void redisFailureDoesNotLoseConfirmation() throws Exception {
        // 테스트 목적:
        // Redis 장애가 잘못된 FE ACK 또는 확인 순번의 영구 유실로 이어지지 않는지 검증한다.
        // given
        sendThrough(10);
        when(repository.saveIfOwner(88L, null, 10L)).thenThrow(new RedisConnectionFailureException("down")).thenReturn(true);
        // when
        ack(9);
        // then
        verifyNoInteractions(feSocket);
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(9);
        assertThat(audioRegistry.streamState(88L).lastProcessedSequence()).isZero();
        assertThat(connection.pendingAudioCount()).isEqualTo(10);
        // when
        ack(9);
        // then
        assertThat(audioRegistry.streamState(88L).lastProcessedSequence()).isEqualTo(10);
        verify(feSocket).sendMessage(any(TextMessage.class));
        assertThat(connection.pendingAudioCount()).isZero();
    }

    @Test
    @DisplayName("Redis Owner가 바뀌어 checkpoint가 거절되면 FE ACK도 전송하지 않는다")
    void ownerMismatchSuppressesAck() throws Exception {
        // 테스트 목적:
        // 다른 BE가 소유권을 가진 녹음에 이전 BE가 확인 응답을 반영하지 않는지 검증한다.
        // given
        connection.assignOwner("old-owner");
        sendThrough(10);
        when(repository.saveIfOwner(88L, "old-owner", 10L)).thenReturn(false);
        // when
        ack(9);
        // then
        verifyNoInteractions(feSocket);
        assertThat(audioRegistry.streamState(88L).lastProcessedSequence()).isZero();
        assertThat(metrics.get("ai.audio.ack.checkpoint.failures").counter().count()).isOne();
    }

    @Test
    @DisplayName("확인되지 않은 PCM이 5초 동안 진행하지 않으면 timeout을 한 번만 알린다")
    void detectsTimeoutWithoutRecovery() throws Exception {
        // 테스트 목적:
        // 실제 pending이 있는 연결에서만 원인을 알리고 연결 종료나 재연결은 하지 않는지 검증한다.
        // given
        sendThrough(1);
        // when
        clock.advance(5);
        service.detectTimeouts();
        service.detectTimeouts();
        // then
        assertThat(events).hasSize(1);
        assertThat(((AiAudioAckTimeoutEvent) events.getFirst()).reason()).isEqualTo("AUDIO_ACK_TIMEOUT");
        assertThat(connection.isClosed()).isFalse();
        verify(aiSocket, never()).close(any());
        verifyNoInteractions(feSocket);
        assertThat(metrics.get("ai.audio.ack.timeouts").counter().count()).isOne();
    }

    @Test
    @DisplayName("신규 PCM이 없는 무음과 모든 청크가 확인된 연결은 timeout이 없다")
    void silenceAndNoPendingAreHealthy() throws Exception {
        // 테스트 목적:
        // 단순히 AI 응답이 없다는 이유로 정상 무음을 장애로 오판하지 않는지 검증한다.
        // given
        clock.advance(3600);
        // when
        service.detectTimeouts();
        sendThrough(1);
        service.detectTimeouts();
        ack(0);
        clock.advance(3600);
        service.detectTimeouts();
        // then
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("PAUSED 상태에서 신규 PCM이 없으면 ACK timeout을 발생시키지 않는다")
    void pausedDoesNotTimeout() throws Exception {
        // 테스트 목적:
        // 사용자 일시정지 상태의 연결을 정상적인 진행 중 연결과 구분하는지 검증한다.
        // given
        sendThrough(1);
        ack(0);
        connection.sendPause(mapper, "pause");
        connection.markPaused();
        // when
        clock.advance(30);
        service.detectTimeouts();
        // then
        assertThat(events).isEmpty();
    }

    @Test
    @DisplayName("PAUSE 이전의 미확인 PCM은 ACK된 것으로 취급하지 않고 timeout을 감시한다")
    void pendingBeforePauseStillRequiresAck() throws Exception {
        // 테스트 목적:
        // 일시정지가 이전 오디오 수신 확인을 대신하거나 pending을 조용히 없애지 않는지 검증한다.
        // given
        sendThrough(1);
        connection.sendPause(mapper, "pause");
        connection.markPaused();
        // when
        clock.advance(5);
        service.detectTimeouts();
        // then
        assertThat(events).hasSize(1);
        assertThat(connection.ackSnapshot().lastSentSequence()).isZero();
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(-1);
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.PAUSED);
    }

    @Test
    @DisplayName("오래된 ACK 이후 새 PCM을 보내면 새 pending 시점부터 5초를 계산한다")
    void firstPendingAfterOldAckStartsNewDeadline() throws Exception {
        // 테스트 목적:
        // 오랜 무음 뒤의 첫 청크가 오래된 ACK 시각 때문에 즉시 timeout으로 판정되지 않는지 검증한다.
        // given
        sendThrough(1);
        ack(0);
        clock.advance(30);
        assertThat(connection.ackSnapshot().lastAckProgressAt()).isNull();
        // when
        service.forwardAudio(connection, mapper, 2, new byte[]{1});
        service.detectTimeouts();
        clock.advance(4);
        service.detectTimeouts();
        // then
        assertThat(events).isEmpty();
        assertThat(connection.ackSnapshot().lastAckProgressAt()).isEqualTo(clock.instant().minusSeconds(4));
        // when
        clock.advance(1);
        service.detectTimeouts();
        // then
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("timeout 직전 ACK 진행은 기준 시각을 갱신하고 다음 pending을 계속 감시한다")
    void advancingAckResetsDeadline() throws Exception {
        // 테스트 목적:
        // ACK 진행과 timeout 경계에서 정상 진행을 장애로 오판하지 않는지 검증한다.
        // given
        sendThrough(111);
        ack(100);
        clock.advance(3);
        // when
        ack(105);
        clock.advance(3);
        service.detectTimeouts();
        // then
        assertThat(events).isEmpty();
        // when
        clock.advance(2);
        service.detectTimeouts();
        ack(110);
        service.detectTimeouts();
        // then
        assertThat(events).hasSize(1);
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(110);
        assertThat(connection.ackSnapshot().lastAckProgressAt()).isNull();
    }

    @Test
    @DisplayName("미확인 연결의 추가 send나 중복 ACK는 timeout 기한을 미루지 않는다")
    void onlyProgressExtendsDeadline() throws Exception {
        // 테스트 목적:
        // 오디오 전송만 계속되는 먹통을 정상 진행으로 처리하지 않는지 검증한다.
        // given
        sendThrough(2);
        ack(0);
        clock.advance(4);
        // when
        service.forwardAudio(connection, mapper, 3, new byte[]{1});
        ack(0);
        clock.advance(1);
        service.detectTimeouts();
        // then
        assertThat(events).hasSize(1);
    }

    @Test
    @DisplayName("연결 cleanup은 ACK 상태를 제거하고 늦은 응답으로 상태를 재생성하지 않는다")
    void cleanupFencesOldResponses() throws Exception {
        // 테스트 목적:
        // 이전 연결의 응답이 새 연결이나 제거된 녹음 상태에 반영되지 않는지 검증한다.
        // given
        sendThrough(1);
        // when
        service.cleanup(connection);
        connections.remove(88L, connection);
        AiLiveMeetingConnection replacement = ready("connection-b");
        replacement.attach(aiSocket);
        connections.reserve(replacement);
        service.forwardAudio(replacement, mapper, 1, new byte[]{1});
        ack(0);
        // then
        verifyNoInteractions(repository, feSocket);
        assertThat(connection.pendingAudioCount()).isZero();
        assertThat(connection.ackSnapshot().lastAckReceivedAt()).isNull();
        assertThat(connection.ackSnapshot().lastAckProgressAt()).isNull();
        assertThat(replacement.ackSnapshot().lastAckedSequence()).isEqualTo(-1);
    }

    @Test
    @DisplayName("녹음 종료 요청은 남은 ACK 상태를 정리하되 전사 종료 대기는 유지한다")
    void stopClearsPendingWithoutClosingTranscriptChannel() throws Exception {
        // 테스트 목적:
        // 종료된 오디오의 ACK 메모리는 제거하면서 기존 마지막 전사 수신 흐름을 유지하는지 검증한다.
        // given
        sendThrough(1);
        // when
        connection.sendStop(mapper, "stop");
        clock.advance(30);
        service.detectTimeouts();
        ack(0);
        // then
        assertThat(connection.state()).isEqualTo(AiLiveMeetingConnectionState.STOP_SENT);
        assertThat(connection.pendingAudioCount()).isZero();
        assertThat(events).isEmpty();
        verifyNoInteractions(repository, feSocket);
    }

    @Test
    @DisplayName("현재 연결의 ACK 추적은 상한을 넘어 메모리에 무한히 쌓이지 않는다")
    void pendingTrackingIsBounded() throws Exception {
        // 테스트 목적:
        // ACK가 전혀 오지 않는 경우에도 순번 대응 상태가 무한 증가하지 않는지 검증한다.
        // given
        sendThrough(1024);
        // when
        boolean accepted = service.forwardAudio(connection, mapper, 1025, new byte[]{1});
        // then
        assertThat(accepted).isFalse();
        assertThat(connection.pendingAudioCount()).isEqualTo(1024);
        assertThat(connection.ackSnapshot().lastSentSequence()).isEqualTo(1023);
        assertThat(metrics.get("ai.audio.ack.pending.rejected").counter().count()).isOne();
    }

    @Test
    @DisplayName("pending 상한으로 거절한 FE 청크를 나중 순번의 누적 ACK로 건너뛰지 않는다")
    void capacityDoesNotSilentlySkipAudio() throws Exception {
        // 테스트 목적:
        // 로컬 상한으로 전송하지 못한 오디오를 더 높은 누적 ACK로 확인했다고 오인하지 않는지 검증한다.
        // given
        sendThrough(1024);
        assertThat(service.forwardAudio(connection, mapper, 1025, new byte[]{1})).isFalse();
        ack(1023);
        // when
        boolean skipped = service.forwardAudio(connection, mapper, 1026, new byte[]{1});
        boolean retried = service.forwardAudio(connection, mapper, 1025, new byte[]{1});
        // then
        assertThat(skipped).isFalse();
        assertThat(retried).isTrue();
        assertThat(audioRegistry.streamState(88L).lastProcessedSequence()).isEqualTo(1024);
    }

    @Test
    @DisplayName("write 실패는 lastSent를 올리지 않고 실패 청크를 건너뛴 전송을 허용하지 않는다")
    void partialWriteDoesNotBecomeAcknowledged() throws Exception {
        // 테스트 목적:
        // 부분 전송 실패를 성공으로 기록하거나 후속 청크의 ACK로 덮어버리지 않는지 검증한다.
        // given
        doThrow(new java.io.IOException("write failed")).when(aiSocket).sendMessage(any(BinaryMessage.class));
        // when
        assertThatThrownBy(() -> service.forwardAudio(connection, mapper, 1, new byte[]{1}))
                .isInstanceOf(java.io.IOException.class);
        boolean skipped = service.forwardAudio(connection, mapper, 2, new byte[]{1});
        // then
        assertThat(skipped).isFalse();
        assertThat(connection.ackSnapshot().lastSentSequence()).isEqualTo(-1);
        assertThat(connection.pendingAudioCount()).isZero();
        verifyNoInteractions(repository, feSocket);
    }

    @Test
    @DisplayName("Registry에서 현재 AI 연결을 제거하면 연결의 ACK 추적 상태도 제거된다")
    void registryRemovalClearsAckState() throws Exception {
        // 테스트 목적:
        // 명시적 세션 제거 경로에도 확인 대기 상태가 남지 않는지 검증한다.
        // given
        sendThrough(1);
        // when
        connections.remove(88L, connection);
        // then
        assertThat(connection.isClosed()).isTrue();
        assertThat(connection.pendingAudioCount()).isZero();
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(-1);
    }

    @Test
    @DisplayName("동시 send와 즉시 도착한 ACK는 전송 완료 이후에만 checkpoint를 진행한다")
    void ackCannotOvertakeSend() throws Exception {
        // 테스트 목적:
        // 다른 스레드의 AI 응답이 lastSent 갱신보다 먼저 반영되지 않는지 검증한다.
        // given
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> { writing.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return null; })
                .when(aiSocket).sendMessage(any(BinaryMessage.class));
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // when
            Future<?> send = pool.submit(() -> { try { sendThrough(1); } catch (Exception e) { throw new AssertionError(e); } });
            assertThat(writing.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> ack = pool.submit(() -> ack(0));
            try {
                // then
                assertThatThrownBy(() -> ack.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            send.get(3, TimeUnit.SECONDS);
            ack.get(3, TimeUnit.SECONDS);
        }
        assertThat(connection.ackSnapshot().lastAckedSequence()).isZero();
        verify(repository).saveIfOwner(88L, null, 1L);
    }

    @Test
    @DisplayName("최신 ACK와 과거 ACK가 경쟁해도 checkpoint 호출 순번은 역행하지 않는다")
    void concurrentAcksAreMonotonic() throws Exception {
        // 테스트 목적:
        // 역순으로 섞인 응답을 여러 스레드가 처리해도 확인 상태가 단조 증가하는지 검증한다.
        // given
        sendThrough(100);
        try (ExecutorService pool = Executors.newFixedThreadPool(8)) {
            // when
            List<Future<?>> tasks = new java.util.ArrayList<>();
            for (int i = 99; i >= 0; i--) { int sequence = i; tasks.add(pool.submit(() -> ack(sequence))); }
            for (var task : tasks) { task.get(3, TimeUnit.SECONDS); }
        }
        // then
        assertThat(connection.ackSnapshot().lastAckedSequence()).isEqualTo(99);
        ArgumentCaptor<Long> saved = ArgumentCaptor.forClass(Long.class);
        verify(repository, atLeastOnce()).saveIfOwner(eq(88L), isNull(), saved.capture());
        assertThat(saved.getAllValues()).isSorted();
        assertThat(audioRegistry.streamState(88L).lastProcessedSequence()).isEqualTo(100);
    }

    @Test
    @DisplayName("ACK 처리 중 cleanup이 경쟁해도 종료 후 checkpoint가 다시 기록되지 않는다")
    void cleanupRacesWithAckSafely() throws Exception {
        // 테스트 목적:
        // checkpoint 도중 연결을 정리하더라도 종료된 상태가 다시 생기지 않는지 검증한다.
        // given
        sendThrough(1);
        CountDownLatch saving = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(repository.saveIfOwner(88L, null, 1L)).thenAnswer(call -> {
            saving.countDown(); assertThat(release.await(3, TimeUnit.SECONDS)).isTrue(); return true;
        });
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            // when
            Future<?> ack = pool.submit(() -> ack(0));
            assertThat(saving.await(2, TimeUnit.SECONDS)).isTrue();
            Future<?> cleanup = pool.submit(() -> service.cleanup(connection));
            try {
                assertThatThrownBy(() -> cleanup.get(50, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally { release.countDown(); }
            ack.get(3, TimeUnit.SECONDS);
            cleanup.get(3, TimeUnit.SECONDS);
        }
        ack(0);
        // then
        verify(repository, times(1)).saveIfOwner(88L, null, 1L);
        assertThat(audioRegistry.findStreamState(88L)).isEmpty();
        assertThat(connection.pendingAudioCount()).isZero();
    }

    @Test
    @DisplayName("AI inbound audio.ack는 기존 연결 서비스의 전용 처리 경로로 전달된다")
    void inboundRoutesAudioAck() throws Exception {
        // 테스트 목적:
        // 실제 AI 메시지 분기에서 audio.ack를 알 수 없는 메시지로 버리지 않는지 검증한다.
        // given
        var connectionService = mock(AiLiveMeetingConnectionService.class);
        when(connectionService.isCurrent(connection)).thenReturn(true);
        var handler = new AiLiveMeetingInboundHandler(connection, connectionService, mapper, mock(TranscriptSaveDispatcher.class));
        String message = "{\"type\":\"audio.ack\",\"recordingSessionId\":\"88\",\"payload\":{\"sequence\":0}}";
        // when
        handler.handleTextMessage(aiSocket, new TextMessage(message));
        // then
        verify(connectionService).receiveAudioAck(connection, mapper.readTree(message));
        verify(connectionService, never()).cleanup(any());
    }

    private void sendThrough(int count) throws Exception {
        for (long sequence = 1; sequence <= count; sequence++) {
            assertThat(service.forwardAudio(connection, mapper, sequence, new byte[]{1})).isTrue();
        }
    }

    private void ack(long sequence) {
        service.receive(connection, mapper.readTree("{\"recordingSessionId\":\"88\",\"payload\":{\"sequence\":" + sequence + "}}"), mapper);
    }

    private AiLiveMeetingConnection ready(String id) {
        var result = new AiLiveMeetingConnection(88L, 42L, id);
        result.markReady();
        return result;
    }

    static class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-20T00:00:00Z");
        void advance(long seconds) { now = now.plusSeconds(seconds); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
