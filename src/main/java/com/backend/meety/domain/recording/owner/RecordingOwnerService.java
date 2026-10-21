package com.backend.meety.domain.recording.owner;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecordingOwnerService {

    private final ServerInstance serverInstance;
    private final RecordingOwnerRepository ownerRepository;
    private final AiLiveMeetingConnectionRegistry aiConnectionRegistry;
    private final RecordingCommandPublisher commandPublisher;

    public boolean register(Long recordingSessionId) {
        String owner = serverInstance.nextOwner();
        try {
            ownerRepository.save(recordingSessionId, owner);
        } catch (DataAccessException e) {
            log.error("녹음 세션 Owner 기록에 실패했습니다. recordingSessionId={}, owner={}",
                    recordingSessionId, owner, e);
            return false;
        }
        aiConnectionRegistry.assignOwner(recordingSessionId, owner);
        log.info("녹음 세션 Owner를 기록했습니다. recordingSessionId={}, owner={}", recordingSessionId, owner);
        return true;
    }

    public RecordingCommandDispatch dispatch(Long recordingSessionId, RecordingCommand command) {
        try {
            Optional<String> owner = ownerRepository.find(recordingSessionId);
            if (owner.isEmpty()) {
                log.info("Owner가 없어 녹음 제어 명령을 보내지 않습니다. recordingSessionId={}, command={}",
                        recordingSessionId, command);
                return RecordingCommandDispatch.NO_OWNER;
            }
            commandPublisher.publish(new RecordingCommandMessage(recordingSessionId, command, owner.get()));
            return RecordingCommandDispatch.SENT;
        } catch (DataAccessException e) {
            log.error("녹음 제어 명령 전달에 실패했습니다. recordingSessionId={}, command={}",
                    recordingSessionId, command, e);
            return RecordingCommandDispatch.FAILED;
        }
    }

    public void refreshOwners() {
        aiConnectionRegistry.findAll().stream()
                .filter(connection -> connection.owner() != null && !connection.isClosed())
                .forEach(connection -> refresh(connection.recordingSessionId(), connection.owner()));
    }

    public void release(Long recordingSessionId, String owner) {
        try {
            if (ownerRepository.deleteIfOwner(recordingSessionId, owner)) {
                log.info("녹음 세션 Owner를 해제했습니다. recordingSessionId={}, owner={}", recordingSessionId, owner);
                return;
            }
            log.info("현재 Owner가 아니어서 Owner를 해제하지 않았습니다. recordingSessionId={}, owner={}",
                    recordingSessionId, owner);
        } catch (DataAccessException e) {
            log.warn("녹음 세션 Owner 해제에 실패했습니다. recordingSessionId={}, owner={}", recordingSessionId, owner, e);
        }
    }

    private void refresh(Long recordingSessionId, String owner) {
        try {
            if (!ownerRepository.refreshIfOwner(recordingSessionId, owner)) {
                log.warn("Redis Owner가 없거나 다른 연결이라 갱신하지 않았습니다. recordingSessionId={}, owner={}",
                        recordingSessionId, owner);
            }
        } catch (DataAccessException e) {
            log.warn("녹음 세션 Owner 갱신에 실패했습니다. recordingSessionId={}, owner={}", recordingSessionId, owner, e);
        }
    }
}
