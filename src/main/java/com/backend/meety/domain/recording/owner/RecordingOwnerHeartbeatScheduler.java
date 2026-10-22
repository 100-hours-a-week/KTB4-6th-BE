package com.backend.meety.domain.recording.owner;

import com.backend.meety.global.config.SchedulingConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class RecordingOwnerHeartbeatScheduler {

    private final RecordingOwnerService ownerService;

    @Scheduled(fixedDelayString = "${recording.owner.heartbeat-interval}", scheduler = SchedulingConfig.CONNECTION_TASK_SCHEDULER_BEAN_NAME)
    public void refreshOwners() {
        ownerService.refreshOwners();
    }
}
