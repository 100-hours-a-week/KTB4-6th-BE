package com.backend.meety.domain.recording.repository;

import com.backend.meety.domain.recording.entity.RecordingSessionStatus;

public record RecordingStatusSnapshot(Long id, Long meetingId, RecordingSessionStatus status) {
}
