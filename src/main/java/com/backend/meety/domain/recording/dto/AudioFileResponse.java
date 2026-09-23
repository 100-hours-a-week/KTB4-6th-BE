package com.backend.meety.domain.recording.dto;

import com.backend.meety.domain.recording.entity.AudioFile;
import com.backend.meety.domain.recording.entity.AudioFileStatus;
import java.time.LocalDateTime;

public record AudioFileResponse(
        Long audioFileId,
        AudioFileStatus status,
        String contentType,
        Long fileSizeBytes,
        Long durationMs,
        LocalDateTime storedAt,
        LocalDateTime expiresAt
) {

    public static AudioFileResponse from(AudioFile audioFile) {
        return new AudioFileResponse(
                audioFile.getId(),
                audioFile.getStatus(),
                audioFile.getContentType(),
                audioFile.getFileSizeBytes(),
                audioFile.getDurationMs(),
                audioFile.getStoredAt(),
                audioFile.getExpiresAt()
        );
    }
}
