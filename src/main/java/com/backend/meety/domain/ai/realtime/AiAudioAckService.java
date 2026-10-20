package com.backend.meety.domain.ai.realtime;

import com.backend.meety.domain.ai.event.AiAudioAckTimeoutEvent;
import com.backend.meety.domain.recording.realtime.AudioAckMessage;
import com.backend.meety.domain.recording.realtime.AudioSequenceRepository;
import com.backend.meety.domain.recording.realtime.AudioStreamState;
import com.backend.meety.domain.recording.realtime.AudioWebSocketRegistry;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.time.Clock;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Service
public class AiAudioAckService {
    private final AiLiveMeetingConnectionRegistry connections;
    private final AudioWebSocketRegistry audioRegistry;
    private final AudioSequenceRepository sequenceRepository;
    private final AiAudioAckProperties properties;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final Counter received;
    private final Counter invalid;
    private final Counter checkpointFailures;
    private final Counter saturated;
    private final Counter timeouts;
    private final Map<AiLiveMeetingConnection.AckResult, Counter> outcomes =
            new EnumMap<>(AiLiveMeetingConnection.AckResult.class);

    public AiAudioAckService(AiLiveMeetingConnectionRegistry connections, AudioWebSocketRegistry audioRegistry,
                             AudioSequenceRepository sequenceRepository, AiAudioAckProperties properties,
                             Clock clock, MeterRegistry metrics, ApplicationEventPublisher events) {
        this.connections = connections;
        this.audioRegistry = audioRegistry;
        this.sequenceRepository = sequenceRepository;
        this.properties = properties;
        this.clock = clock;
        this.events = events;
        received = metrics.counter("ai.audio.ack.received");
        invalid = metrics.counter("ai.audio.ack.invalid");
        checkpointFailures = metrics.counter("ai.audio.ack.checkpoint.failures");
        saturated = metrics.counter("ai.audio.ack.pending.rejected");
        timeouts = metrics.counter("ai.audio.ack.timeouts");
        for (var result : AiLiveMeetingConnection.AckResult.values()) {
            outcomes.put(result, metrics.counter("ai.audio.ack.outcomes", "result", result.name().toLowerCase(Locale.ROOT)));
        }
        Gauge.builder("ai.audio.ack.lag", connections,
                registry -> registry.findAll().stream().mapToLong(c -> c.ackSnapshot().lag()).sum()).register(metrics);
    }

    public boolean forwardAudio(AiLiveMeetingConnection connection, ObjectMapper mapper,
                                long feSequence, byte[] audio) throws IOException {
        AudioStreamState state = audioRegistry.streamState(connection.recordingSessionId());
        synchronized (state) {
            if (!isCurrent(connection) || !state.bind(connection.sessionStartRequestId()) || state.isSent(feSequence)) {
                return false;
            }
            boolean sent = connection.forwardAudio(mapper, audio, feSequence, clock,
                    properties.maxPendingChunks());
            if (sent) {
                state.markSent(feSequence);
            } else if (connection.pendingAudioCount() >= properties.maxPendingChunks()) {
                saturated.increment();
                log.warn("AI Audio ACK 확인 대기 상한에 도달했습니다. recordingSessionId={}", connection.recordingSessionId());
            }
            return sent;
        }
    }

    public void receive(AiLiveMeetingConnection connection, JsonNode message, ObjectMapper mapper) {
        received.increment();
        JsonNode sessionId = message.path("recordingSessionId");
        JsonNode sequence = message.path("payload").path("sequence");
        if (!String.valueOf(connection.recordingSessionId()).equals(sessionId.asText())
                || !sequence.isIntegralNumber() || !sequence.canConvertToLong() || sequence.longValue() < 0) {
            invalid.increment();
            log.warn("잘못된 AI audio.ack를 무시합니다. recordingSessionId={}", connection.recordingSessionId());
            return;
        }
        AudioStreamState state = audioRegistry.findStreamState(connection.recordingSessionId()).orElse(null);
        if (state == null) {
            outcomes.get(AiLiveMeetingConnection.AckResult.CLOSED).increment();
            return;
        }
        synchronized (state) {
            if (!isCurrent(connection) || !state.belongsTo(connection.sessionStartRequestId())) {
                outcomes.get(AiLiveMeetingConnection.AckResult.CLOSED).increment();
                return;
            }
            try {
                var result = connection.acknowledge(sequence.longValue(), clock.instant(), (feSequence, count) -> {
                    if (!sequenceRepository.saveIfOwner(connection.recordingSessionId(), connection.owner(), feSequence)) {
                        checkpointFailures.increment();
                        log.warn("AI ACK checkpoint가 거절되어 FE ACK를 보내지 않습니다. recordingSessionId={}, sequence={}",
                                connection.recordingSessionId(), feSequence);
                        return false;
                    }
                    boolean ackDue = state.markProcessed(feSequence, count);
                    if (ackDue) {
                        audioRegistry.find(connection.recordingSessionId()).ifPresent(session -> {
                            try {
                                synchronized (session) {
                                    session.sendMessage(new TextMessage(mapper.writeValueAsString(AudioAckMessage.create(feSequence))));
                                }
                            } catch (Exception e) {
                                log.warn("FE 누적 ACK 전송에 실패했습니다. recordingSessionId={}, sequence={}",
                                        connection.recordingSessionId(), feSequence, e);
                            }
                        });
                    }
                    return true;
                });
                outcomes.get(result).increment();
                if (result == AiLiveMeetingConnection.AckResult.INVALID) {
                    invalid.increment();
                    log.warn("미전송/유효하지 않은 순번의 AI ACK를 무시합니다. recordingSessionId={}, sequence={}, lastSent={}",
                            connection.recordingSessionId(), sequence.longValue(), connection.ackSnapshot().lastSentSequence());
                }
                log.debug("AI audio.ack. recordingSessionId={}, sequence={}, result={}",
                        connection.recordingSessionId(), sequence.longValue(), result);
            } catch (DataAccessException e) {
                checkpointFailures.increment();
                log.warn("AI ACK checkpoint 저장에 실패해 FE ACK를 보내지 않습니다. recordingSessionId={}, sequence={}",
                        connection.recordingSessionId(), sequence.longValue(), e);
            }
        }
    }

    @Scheduled(fixedDelayString = "${ai.audio-ack.poll-delay}")
    public void detectTimeouts() {
        for (var connection : connections.findAll()) {
            AtomicReference<AiLiveMeetingConnection.AckSnapshot> detected = new AtomicReference<>();
            connection.detectAckTimeout(clock.instant(), properties.timeout(), detected::set);
            var snapshot = detected.get();
            if (snapshot != null) {
                timeouts.increment();
                log.warn("AUDIO_ACK_TIMEOUT recordingSessionId={}, connection={}, lastSent={}, lastAcked={}, lag={}",
                        connection.recordingSessionId(), connection.sessionStartRequestId(), snapshot.lastSentSequence(),
                        snapshot.lastAckedSequence(), snapshot.lag());
                events.publishEvent(new AiAudioAckTimeoutEvent(connection.recordingSessionId(),
                        connection.sessionStartRequestId(), snapshot.lastSentSequence(), snapshot.lastAckedSequence(), clock.instant()));
            }
        }
    }

    public void cleanup(AiLiveMeetingConnection connection) {
        audioRegistry.findStreamState(connection.recordingSessionId()).ifPresent(state -> {
            synchronized (state) {
                if (state.belongsTo(connection.sessionStartRequestId())) {
                    connection.close();
                    audioRegistry.removeStreamState(connection.recordingSessionId(), state);
                }
            }
        });
        connection.close();
    }

    private boolean isCurrent(AiLiveMeetingConnection connection) {
        return !connection.isClosed() && connections.find(connection.recordingSessionId()).orElse(null) == connection;
    }
}
