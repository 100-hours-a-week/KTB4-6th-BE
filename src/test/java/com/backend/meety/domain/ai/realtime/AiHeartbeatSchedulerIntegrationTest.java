package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.backend.meety.domain.transcript.realtime.TranscriptSaveDispatcher;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.ObjectMapper;

class AiHeartbeatSchedulerIntegrationTest {
    @Test
    @DisplayName("실제 Spring scheduler가 ping을 주기적으로 보내고 다른 스레드의 inbound pong을 처리한다")
    void schedulerAndInboundWorkTogether() throws Exception {
        // 테스트 목적:
        // @Scheduled 등록과 비동기 AI 응답 경로가 연결되어 반복 probe를 수행하는지 검증한다.
        // given
        var connections = new AiLiveMeetingConnectionRegistry();
        var connection = new AiLiveMeetingConnection(88L, 42L, "scheduler-connection");
        var mapper = new ObjectMapper();
        var metrics = new SimpleMeterRegistry();
        var events = new CopyOnWriteArrayList<Object>();
        var service = new AiHeartbeatService(connections,
                new AiHeartbeatProperties(Duration.ofMillis(20), Duration.ofSeconds(2), 2),
                new AiRequestIdGenerator(), mapper, Clock.systemUTC(), metrics, events::add);
        var bridge = mock(AiLiveMeetingConnectionService.class);
        when(bridge.isCurrent(connection)).thenReturn(true);
        var inbound = new AiLiveMeetingInboundHandler(connection, bridge, mapper, mock(TranscriptSaveDispatcher.class));
        var socket = mock(WebSocketSession.class);
        var responses = new CountDownLatch(2);
        var failure = new AtomicReference<Throwable>();
        when(socket.isOpen()).thenReturn(true);
        doAnswer(call -> {
            service.receivePong(call.getArgument(0), call.getArgument(1));
            responses.countDown();
            return null;
        }).when(bridge).receiveHeartbeatPong(any(), any());
        connection.attach(socket);
        connections.reserve(connection);
        try (var aiThread = Executors.newSingleThreadExecutor();
             var context = new AnnotationConfigApplicationContext()) {
            doAnswer(call -> {
                var ping = mapper.readTree(((TextMessage) call.getArgument(0)).getPayload());
                String response = mapper.writeValueAsString(Map.of(
                        "type", "heartbeat.pong", "requestId", ping.path("requestId").asText(), "recordingSessionId", "88"));
                aiThread.submit(() -> {
                    try { inbound.handleTextMessage(socket, new TextMessage(response)); }
                    catch (Throwable e) { failure.set(e); }
                });
                return null;
            }).when(socket).sendMessage(any(TextMessage.class));
            context.getEnvironment().getPropertySources().addFirst(new MapPropertySource(
                    "test", Map.of("ai.heartbeat.interval", "20ms")));
            context.register(Scheduling.class);
            context.registerBean(AiHeartbeatService.class, () -> service);
            context.refresh();
            // when
            connection.markReady();
            boolean received = responses.await(3, TimeUnit.SECONDS);
            connections.remove(88L, connection);
            // then
            assertThat(received).isTrue();
            assertThat(failure.get()).isNull();
            assertThat(metrics.counter("ai.heartbeat.pings").count()).isGreaterThanOrEqualTo(2);
            assertThat(metrics.counter("ai.heartbeat.pongs").count()).isGreaterThanOrEqualTo(2);
            assertThat(events).isEmpty();
            assertThat(connection.heartbeatSnapshot().lastPingRequestId()).isNull();
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableScheduling
    static class Scheduling { }
}
