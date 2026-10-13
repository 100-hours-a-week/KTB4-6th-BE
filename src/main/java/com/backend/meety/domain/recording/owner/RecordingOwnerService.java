package com.backend.meety.domain.recording.owner;

import com.backend.meety.domain.ai.realtime.AiLiveMeetingConnectionRegistry;
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

    public boolean register(Long recordingSessionId) {
        String owner = serverInstance.nextOwner();
        try {
            ownerRepository.save(recordingSessionId, owner);
        } catch (DataAccessException e) {
            log.error("녹음 세션 Owner 기록에 실패했습니다. recordingSessionId={}, owner={}",
                    recordingSessionId, owner, e);
            return false;
        }
        aiConnectionRegistry.find(recordingSessionId).ifPresent(connection -> connection.assignOwner(owner));
        log.info("녹음 세션 Owner를 기록했습니다. recordingSessionId={}, owner={}", recordingSessionId, owner);
        return true;
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
