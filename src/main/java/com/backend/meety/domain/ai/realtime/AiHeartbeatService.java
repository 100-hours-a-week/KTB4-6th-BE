package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.event.AiHeartbeatTimeoutEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class AiHeartbeatService {
    private final AiLiveMeetingConnectionRegistry connections;
    private final AiHeartbeatProperties properties;
    private final AiRequestIdGenerator requestIds;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Counter pings;
    private final Counter pongs;
    private final Counter invalidPongs;
    private final Counter timeouts;
    private final Counter thresholdReached;
    private final Counter sendFailures;

    public AiHeartbeatService(AiLiveMeetingConnectionRegistry connections, AiHeartbeatProperties properties,
                              AiRequestIdGenerator requestIds, ObjectMapper mapper, Clock clock,
                              MeterRegistry metrics, ApplicationEventPublisher events) {
        this.connections = connections;
        this.properties = properties;
        this.requestIds = requestIds;
        this.mapper = mapper;
        this.clock = clock;
        this.events = events;
        pings = metrics.counter("ai.heartbeat.pings");
        pongs = metrics.counter("ai.heartbeat.pongs");
        invalidPongs = metrics.counter("ai.heartbeat.pongs.invalid");
        timeouts = metrics.counter("ai.heartbeat.timeouts");
        thresholdReached = metrics.counter("ai.heartbeat.threshold.reached");
        sendFailures = metrics.counter("ai.heartbeat.send.failures");
    }

    @Scheduled(fixedDelayString = "${ai.heartbeat.interval}")
    public void tick() {
        for (var connection : connections.findAll()) {
            try {
                process(connection);
            } catch (Exception e) {
                // A bad connection or a downstream event listener must not stop scanning other connections.
                log.warn("AI Heartbeat 검사에 실패했습니다. recordingSessionId={}", connection.recordingSessionId(), e);
            }
        }
    }

    private void process(AiLiveMeetingConnection connection) {
        var result = connection.heartbeatTick(mapper, properties, clock, requestIds::heartbeatRequestId,
                () -> isCurrent(connection));
        if (result.pingSent()) {
            pings.increment();
            log.debug("AI heartbeat.ping 전송. recordingSessionId={}, connection={}",
                    connection.recordingSessionId(), connection.sessionStartRequestId());
        }
        if (result.sendFailure() != null) {
            sendFailures.increment();
            log.warn("AI heartbeat.ping 전송에 실패했습니다. recordingSessionId={}",
                    connection.recordingSessionId(), result.sendFailure());
        }
        var timeout = result.timeout();
        if (timeout != null) {
            timeouts.increment();
            log.warn("AI heartbeat.pong 대기 시간 초과. recordingSessionId={}, connection={}, requestId={}, failures={}",
                    connection.recordingSessionId(), connection.sessionStartRequestId(),
                    timeout.requestId(), timeout.consecutiveFailures());
            if (timeout.thresholdReached()) {
                thresholdReached.increment();
                log.warn("AI_HEARTBEAT_TIMEOUT recordingSessionId={}, connection={}, failures={}",
                        connection.recordingSessionId(), connection.sessionStartRequestId(), timeout.consecutiveFailures());
                events.publishEvent(new AiHeartbeatTimeoutEvent(connection.recordingSessionId(),
                        connection.sessionStartRequestId(), timeout.requestId(), timeout.consecutiveFailures(), timeout.detectedAt()));
            }
        }
    }

    public void receivePong(AiLiveMeetingConnection connection, JsonNode message) {
        JsonNode requestId = message.path("requestId");
        JsonNode sessionId = message.path("recordingSessionId");
        if (!"heartbeat.pong".equals(message.path("type").asText())
                || !requestId.isString() || requestId.asText().isBlank()
                || !(sessionId.isString() || sessionId.isIntegralNumber())
                || !String.valueOf(connection.recordingSessionId()).equals(sessionId.asText())) {
            invalidPongs.increment();
            log.warn("잘못된 AI heartbeat.pong를 무시합니다. recordingSessionId={}", connection.recordingSessionId());
            return;
        }
        var result = connection.receiveHeartbeatPong(requestId.asText(), clock, properties.pongTimeout(),
                () -> isCurrent(connection));
        if (result == AiLiveMeetingConnection.PongResult.ACCEPTED) {
            pongs.increment();
            log.debug("AI heartbeat.pong 수신. recordingSessionId={}, requestId={}",
                    connection.recordingSessionId(), requestId.asText());
        } else {
            invalidPongs.increment();
            log.warn("이전/만료된 AI heartbeat.pong를 무시합니다. recordingSessionId={}, requestId={}, result={}",
                    connection.recordingSessionId(), requestId.asText(), result);
        }
    }

    private boolean isCurrent(AiLiveMeetingConnection connection) {
        return connections.find(connection.recordingSessionId()).orElse(null) == connection;
    }
}
