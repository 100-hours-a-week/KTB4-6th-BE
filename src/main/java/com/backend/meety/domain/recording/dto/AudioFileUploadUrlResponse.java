package com.backend.meety.domain.recording.dto;

import java.time.LocalDateTime;

public record AudioFileUploadUrlResponse(
        Long audioFileId,
        String uploadUrl,
        LocalDateTime uploadUrlExpiresAt
) {
}
