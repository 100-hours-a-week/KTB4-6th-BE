package com.backend.meety.domain.recording.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record AudioFileUploadCompleteRequest(
        @NotNull
        @Positive
        Long fileSizeBytes,

        @NotNull
        @Positive
        Long durationMs
) {
}
