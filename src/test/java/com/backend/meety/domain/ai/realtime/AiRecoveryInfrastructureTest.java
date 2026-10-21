package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.net.URI;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.WebSocketClient;

class AiRecoveryInfrastructureTest {
    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("Recovery attempt·기간·성공 청크·worker·queue 설정은 양수여야 한다")
    void invalidSettingsAreRejected(int value) {
        // 테스트 목적:
        // 무한 retry나 실행 불가능한 감시 설정을 시작 시 거부하는지 검증한다.
        // given
        var valid = Duration.ofSeconds(5);
        // when, then
        assertThatThrownBy(() -> new AiRecoveryProperties(value, valid, 10, 2, 64)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRecoveryProperties(2, Duration.ofSeconds(value), 10, 2, 64)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRecoveryProperties(2, valid, value, 2, 64)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRecoveryProperties(2, valid, 10, value, 64)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRecoveryProperties(2, valid, 10, 2, value)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("재연결 deadline 뒤 도착한 실제 client future의 WebSocket은 즉시 닫는다")
    void timedOutConnectClosesLateSocket() throws Exception {
        // 테스트 목적:
        // connect 대기가 worker를 무한 점유하거나 취소된 connection을 뒤늦게 부활시키지 않는지 검증한다.
        // given
        var client = mock(WebSocketClient.class);
        var handler = mock(WebSocketHandler.class);
        var future = new CompletableFuture<WebSocketSession>();
        when(client.execute(eq(handler), anyString(), any(Object[].class))).thenReturn(future);
        var adapter = new StandardAiLiveMeetingWebSocketClient(client);
        // when, then
        assertThatThrownBy(() -> adapter.connect(handler, URI.create("ws://localhost/ai"), Duration.ofMillis(10)))
                .isInstanceOf(java.util.concurrent.CompletionException.class);
        // when
        var late = mock(WebSocketSession.class);
        future.complete(late);
        // then
        verify(late).close();
    }

    @Test
    @DisplayName("취소된 Recovery timer는 재사용하는 scheduler 큐에서도 제거된다")
    void cancelledTimerIsRemovedFromQueue() {
        // 테스트 목적:
        // 완료된 세션의 Context를 취소 timer가 계속 참조하지 않는지 검증한다.
        // given
        var scheduler = new DefaultAiStopTimeoutScheduler();
        try {
            var future = scheduler.schedule(() -> { }, Duration.ofHours(1));
            var executor = (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(scheduler, "executor");
            // when
            future.cancel(false);
            // then
            assertThat(executor.getQueue()).isEmpty();
        } finally { scheduler.shutdown(); }
    }
}
