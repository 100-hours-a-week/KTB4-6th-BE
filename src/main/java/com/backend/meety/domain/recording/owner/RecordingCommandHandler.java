package com.backend.meety.domain.recording.owner;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnection;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry;
import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionService;
import com.backend.meety.domain.recording.realtime.AudioWebSocketCloser;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RecordingCommandHandler {

    private final AiLiveMeetingConnectionRegistry aiConnectionRegistry;
    private final AiLiveMeetingConnectionService aiConnectionService;
    private final RecordingOwnerRepository ownerRepository;
    private final AudioWebSocketCloser audioWebSocketCloser;

    public void handle(RecordingCommandMessage message) {
        if (!isCurrentOwner(message)) {
            return;
        }
        Long recordingSessionId = message.recordingSessionId();
        switch (message.command()) {
            case PAUSE -> aiConnectionService.pause(recordingSessionId);
            case RESUME -> aiConnectionService.resume(recordingSessionId);
            case STOP -> {
                aiConnectionService.stop(recordingSessionId);
                audioWebSocketCloser.close(recordingSessionId);
            }
        }
        log.info("녹음 제어 명령을 실행했습니다. recordingSessionId={}, command={}, owner={}",
                recordingSessionId, message.command(), message.owner());
    }

    private boolean isCurrentOwner(RecordingCommandMessage message) {
        String localOwner = aiConnectionRegistry.find(message.recordingSessionId())
                .map(AiLiveMeetingConnection::owner)
                .orElse(null);
        if (!message.owner().equals(localOwner)) {
            log.warn("로컬 AI 연결의 Owner와 달라 녹음 제어 명령을 실행하지 않습니다. recordingSessionId={}, command={}, "
                    + "messageOwner={}, localOwner={}", message.recordingSessionId(), message.command(),
                    message.owner(), localOwner);
            return false;
        }
        Optional<String> redisOwner;
        try {
            redisOwner = ownerRepository.find(message.recordingSessionId());
        } catch (DataAccessException e) {
            log.warn("Redis Owner를 확인하지 못해 녹음 제어 명령을 실행하지 않습니다. recordingSessionId={}, command={}",
                    message.recordingSessionId(), message.command(), e);
            return false;
        }
        if (!redisOwner.filter(message.owner()::equals).isPresent()) {
            log.warn("Redis Owner와 달라 녹음 제어 명령을 실행하지 않습니다. recordingSessionId={}, command={}, "
                    + "messageOwner={}, redisOwner={}", message.recordingSessionId(), message.command(),
                    message.owner(), redisOwner.orElse(null));
            return false;
        }
        return true;
    }
}
