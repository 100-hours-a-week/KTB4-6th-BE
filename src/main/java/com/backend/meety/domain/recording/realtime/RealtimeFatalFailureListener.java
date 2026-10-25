package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.recording.event.RecordingFailedEvent;
import com.backend.meety.domain.recording.owner.RecordingOwnerRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class RealtimeFatalFailureListener {
    private final AiLiveMeetingConnectionService ai;
    private final AudioWebSocketRegistry audio;
    private final RecordingOwnerRepository owners;
    private final ObjectMapper mapper;
    private final MeterRegistry metrics;

    @Order(-10)
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterFailed(RecordingFailedEvent event) {
        metrics.counter("recording.fatal.failures", "reason", event.reason().name()).increment();
        try { ai.closeForFatal(event.recordingSessionId(), event.owner()); }
        catch (RuntimeException e) { log.error("Fatal AI cleanup 실패. recordingSessionId={}", event.recordingSessionId(), e); }
        // Recheck ownership before touching FE runtime; Redis deletion itself is still compare-and-delete.
        boolean cleanupAudio = !ai.hasDifferentOwner(event.recordingSessionId(), event.owner());
        try {
            cleanupAudio &= owners.find(event.recordingSessionId()).map(event.owner()::equals).orElse(true);
        } catch (RuntimeException e) {
            metrics.counter("recording.fatal.owner.cleanup.failures").increment();
            log.warn("Fatal Owner 재검증 실패. 이미 확정된 로컬 종료는 계속합니다. recordingSessionId={}", event.recordingSessionId(), e);
        }
        try {
            if (cleanupAudio) {
                audio.find(event.recordingSessionId()).ifPresent(session -> {
                    try {
                        if (session.isOpen()) {
                            synchronized (session) {
                                session.sendMessage(new TextMessage(mapper.writeValueAsString(
                                        RecordingErrorMessage.create(event.recordingSessionId(), event.reason()))));
                            }
                        }
                    } catch (Exception e) {
                        metrics.counter("recording.fatal.fe.send.failures").increment();
                        log.warn("Fatal FE 오류 전파 실패. recordingSessionId={}", event.recordingSessionId(), e);
                    } finally {
                        audio.closeAndRemove(event.recordingSessionId(), session, CloseStatus.SERVER_ERROR);
                    }
                });
                audio.removeStreamState(event.recordingSessionId());
            }
        } catch (RuntimeException e) {
            log.warn("Fatal FE cleanup 실패. recordingSessionId={}", event.recordingSessionId(), e);
        } finally {
            try { owners.deleteIfOwner(event.recordingSessionId(), event.owner()); }
            catch (RuntimeException e) {
                metrics.counter("recording.fatal.owner.cleanup.failures").increment();
                log.warn("Fatal Owner 해제 실패. recordingSessionId={}", event.recordingSessionId(), e);
            }
        }
    }
}
