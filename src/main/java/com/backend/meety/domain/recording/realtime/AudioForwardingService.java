package com.backend.meety.domain.recording.realtime;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingRegistry;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.exception.RecordingException;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioForwardingService {

    private final RecordingSessionRepository recordingSessionRepository;
    private final AiLiveMeetingRegistry aiLiveMeetingRegistry;

    @Transactional(readOnly = true)
    public boolean forwardIfAllowed(Long recordingSessionId, byte[] audio) {
        RecordingSession session = recordingSessionRepository.findByIdAndDeletedAtIsNull(recordingSessionId)
                .orElseThrow(() -> new RecordingException(RecordingErrorCode.RECORDING_SESSION_NOT_FOUND));
        if (session.getStatus() == RecordingSessionStatus.PAUSED) {
            return false;
        }
        if (session.getStatus() != RecordingSessionStatus.RECORDING) {
            return false;
        }
        AiLiveMeetingConnection connection = aiLiveMeetingRegistry.find(recordingSessionId).orElse(null);
        if (connection == null) {
            return false;
        }
        try {
            return connection.forwardAudio(audio);
        } catch (IOException | IllegalStateException e) {
            log.warn("Audio forwarding에 실패했습니다. recordingSessionId={}", recordingSessionId, e);
            return false;
        }
    }
}
