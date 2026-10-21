package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.backend.meety.domain.ai.event.*;
import com.backend.meety.domain.recording.realtime.RealtimeFailureReason;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.socket.TextMessage;

class AiFatalFailureRoutingTest {
    @ParameterizedTest
    @ValueSource(strings = {"broken", "[]", "{\"type\":\"session.ready\",\"recordingSessionId\":999}"})
    @DisplayName("해석 불가능하거나 현재 세션과 다른 AI 메시지는 Recovery 없이 Fatal로 보낸다")
    void invalidInboundIsFatal(String message) throws Exception {
        // 테스트 목적:
        // 지속할 수 없는 payload가 단순 cleanup이나 재연결로 숨겨지지 않는지 검증한다.
        // given
        var f = new AiRecoveryServiceTest();
        var handler = new AiLiveMeetingInboundHandler(f.original, f.connectionService, f.mapper, f.transcriptSaveDispatcher);
        // when
        handler.handleTextMessage(f.originalSocket, new TextMessage(message));
        // then
        assertThat(f.events).filteredOn(AiFatalFailureEvent.class::isInstance).hasSize(1);
        assertThat(f.events).noneMatch(AiRecoverableFailureEvent.class::isInstance);
        assertThat(f.contexts.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @DisplayName("AI error의 retryable 여부에 따라 즉시 Fatal과 기존 Recovery를 구분한다")
    void aiErrorClassification(boolean retryable) throws Exception {
        // 테스트 목적:
        // 연결 복구가 가능한 오류만 #321로 보내는지 검증한다.
        // given
        var f = new AiRecoveryServiceTest();
        var handler = new AiLiveMeetingInboundHandler(f.original, f.connectionService, f.mapper, f.transcriptSaveDispatcher);
        // when
        handler.handleTextMessage(f.originalSocket, new TextMessage("{\"type\":\"error\",\"recordingSessionId\":88,"
                + "\"payload\":{\"retryable\":" + retryable + "}}"));
        // then
        assertThat(f.events.stream().filter(AiFatalFailureEvent.class::isInstance).count()).isEqualTo(retryable ? 0 : 1);
        assertThat(f.contexts.count()).isEqualTo(retryable ? 1 : 0);
    }

    @Test
    @DisplayName("수락된 전사의 최종 저장 실패는 connection 교체 후에도 원래 Owner의 Fatal로 전달된다")
    void transcriptFailureKeepsSubmissionOwner() throws Exception {
        // 테스트 목적:
        // 비동기 DB 실패가 새 connection의 owner로 잘못 귀속되거나 Recovery로 우회하지 않는지 검증한다.
        // given
        var f = new AiRecoveryServiceTest();
        doThrow(new IllegalArgumentException("invalid transcript")).when(f.transcriptService).saveFinalSegment(any());
        var handler = new AiLiveMeetingInboundHandler(f.original, f.connectionService, f.mapper, f.transcriptSaveDispatcher);
        handler.handleTextMessage(f.originalSocket, new TextMessage("""
                {"type":"transcript.committed","meetingId":42,"recordingSessionId":88,
                 "payload":{"sequenceNumber":12,"content":"test","startedAtMs":0,
                            "endedAtMs":100,"recognizedAt":"2026-10-21T00:00:00Z"}}
                """));
        f.original.assignOwner("new-owner");
        // when
        f.transcriptWork.remove().run();
        // then
        assertThat(f.events).contains(new AiFatalFailureEvent(88L, "owner-a", null,
                RealtimeFailureReason.TRANSCRIPT_PROCESSING_FAILED));
        assertThat(f.contexts.count()).isZero();
    }

    @Test
    @DisplayName("교체된 connection의 늦은 malformed 메시지는 새 세션을 Fatal로 만들지 않는다")
    void oldMalformedMessageIsIgnored() throws Exception {
        // 테스트 목적:
        // 오래된 소켓은 파싱 실패조차 현재 실행의 장애로 반영하지 않는지 검증한다.
        // given
        var f = new AiRecoveryServiceTest();
        var old = new AiLiveMeetingInboundHandler(f.original, f.connectionService, f.mapper, f.transcriptSaveDispatcher);
        f.begin(); f.runAll();
        // when
        old.handleTextMessage(f.originalSocket, new TextMessage("broken"));
        // then
        assertThat(f.events).noneMatch(AiFatalFailureEvent.class::isInstance);
    }

    @Test
    @DisplayName("Fatal cleanup은 Recovery timer를 취소하고 반복 호출 및 늦은 작업에도 재연결하지 않는다")
    void fatalCleanupCancelsRecovery() throws Exception {
        // 테스트 목적:
        // FAILED 확정 후 이전 attempt가 다시 시작되지 않도록 lifecycle이 정리되는지 검증한다.
        // given
        var f = new AiRecoveryServiceTest(); f.begin(); f.runAll();
        var timer = f.contexts.find(88L).orElseThrow().timeout;
        // when
        f.connectionService.closeForFatal(88L, "owner-a");
        f.connectionService.closeForFatal(88L, "owner-a");
        f.clock.advance(10); f.timers.fireAll(); f.runAll();
        // then
        assertThat(f.contexts.count()).isZero();
        assertThat(f.connections.count()).isZero();
        assertThat(timer.isCancelled()).isTrue();
        assertThat(f.starts).hasSize(1);
    }

    @Test
    @DisplayName("이전 Owner Fatal cleanup은 새 Owner의 AI connection을 닫지 않는다")
    void cleanupDoesNotCloseNewOwner() throws Exception {
        // 테스트 목적:
        // 메모리 cleanup에도 Redis 조건부 삭제와 같은 owner 경계가 유지되는지 검증한다.
        // given
        var f = new AiRecoveryServiceTest(); f.original.assignOwner("new-owner");
        // when
        f.connectionService.closeForFatal(88L, "owner-a");
        // then
        assertThat(f.connections.find(88L)).contains(f.original);
        assertThat(f.original.isClosed()).isFalse();
    }

    @Test
    @DisplayName("재연결 대기 중 Fatal cleanup 이후 늦게 연결된 소켓은 session.start 없이 닫힌다")
    void fatalWhileConnectingCannotResurrect() throws Exception {
        // 테스트 목적:
        // 이미 실행 중인 Recovery worker도 Fatal 뒤 connection을 다시 등록하지 못하는지 검증한다.
        // given
        var f = new AiRecoveryServiceTest();
        f.connectEntered = new java.util.concurrent.CountDownLatch(1);
        f.connectRelease = new java.util.concurrent.CountDownLatch(1); f.begin();
        try (var pool = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var connecting = pool.submit(() -> f.work.remove().run());
            assertThat(f.connectEntered.await(2, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            // when
            f.connectionService.closeForFatal(88L, "owner-a");
            f.connectRelease.countDown(); connecting.get(3, java.util.concurrent.TimeUnit.SECONDS); f.runAll();
            // then
            assertThat(f.contexts.count()).isZero(); assertThat(f.connections.count()).isZero();
            assertThat(f.starts).isEmpty(); verify(f.aiSockets.getFirst()).close(org.springframework.web.socket.CloseStatus.NORMAL);
        } finally { f.connectRelease.countDown(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ACK", "HEARTBEAT"})
    @DisplayName("Recovery가 소진돼도 최초 ACK 또는 Heartbeat 장애 원인을 유지한다")
    void exhaustionPreservesInitialReason(String type) throws Exception {
        // 테스트 목적:
        // 최종 connect 실패가 최초 장애 code를 덮어쓰지 않는지 검증한다.
        // given
        var f = new AiRecoveryServiceTest(); f.failConnect = 2;
        // when
        if (type.equals("ACK")) {
            f.ack.forwardAudio(f.original, f.mapper, 100, new byte[]{1});
            f.recovery.onAckTimeout(new AiAudioAckTimeoutEvent(88L, "original", 0, -1, f.clock.instant()));
        } else {
            f.heartbeat.tick(); f.clock.advance(5); f.heartbeat.tick(); f.clock.advance(5); f.heartbeat.tick();
        }
        f.runAll();
        // then
        assertThat(f.events.stream().filter(AiRecoveryExhaustedEvent.class::isInstance)
                .map(AiRecoveryExhaustedEvent.class::cast).map(AiRecoveryExhaustedEvent::reason))
                .containsExactly(type.equals("ACK") ? "AUDIO_ACK_TIMEOUT" : "AI_HEARTBEAT_TIMEOUT");
        assertThat(f.events).noneMatch(AiFatalFailureEvent.class::isInstance);
    }
}
