package com.backend.meety.domain.recording.dto;

import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.AudioFileStatus;
import java.time.LocalDateTime;

public record AudioFileDetailResponse(
        Long audioFileId,
        Long recordingSessionId,
        String contentType,
        Long fileSizeBytes,
        Long durationMs,
        AudioFileStatus status,
        LocalDateTime storedAt,
        LocalDateTime expiresAt
) {

    public static AudioFileDetailResponse from(AudioFile audioFile) {
        return new AudioFileDetailResponse(
                audioFile.getId(),
                audioFile.getRecordingSession().getId(),
                audioFile.getContentType(),
                audioFile.getFileSizeBytes(),
                audioFile.getDurationMs(),
                audioFile.getStatus(),
                audioFile.getStoredAt(),
                audioFile.getExpiresAt()
        );
    }
}
