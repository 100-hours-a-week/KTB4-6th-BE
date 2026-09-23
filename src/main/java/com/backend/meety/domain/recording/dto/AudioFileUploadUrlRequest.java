package com.backend.meety.domain.recording.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AudioFileUploadUrlRequest(
        @NotBlank
        @Size(max = 100)
        String contentType
) {
}
