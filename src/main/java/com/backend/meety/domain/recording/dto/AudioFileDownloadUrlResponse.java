package com.backend.meety.domain.recording.dto;

import java.time.LocalDateTime;

public record AudioFileDownloadUrlResponse(
        String downloadUrl,
        LocalDateTime downloadUrlExpiresAt
) {
}
