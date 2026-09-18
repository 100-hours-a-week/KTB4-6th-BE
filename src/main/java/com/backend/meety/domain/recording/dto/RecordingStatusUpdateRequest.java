package com.backend.meety.domain.recording.dto;

import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public record RecordingStatusUpdateRequest(
        @NotNull
        @Pattern(regexp = "RECORDING|PAUSED|COMPLETED")
        String status
) {

    public RecordingSessionStatus toStatus() {
        return RecordingSessionStatus.valueOf(status);
    }
}
