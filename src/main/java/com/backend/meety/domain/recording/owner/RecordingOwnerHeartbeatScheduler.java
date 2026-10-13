package com.backend.meety.domain.recording.owner;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecordingOwnerHeartbeatScheduler {

    private final RecordingOwnerService ownerService;

    @Scheduled(fixedDelayString = "${recording.owner.heartbeat-interval}")
    public void refreshOwners() {
        ownerService.refreshOwners();
    }
}
