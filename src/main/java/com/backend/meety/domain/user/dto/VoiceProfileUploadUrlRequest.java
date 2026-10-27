package com.backend.meety.domain.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record VoiceProfileUploadUrlRequest(
        @NotBlank
        @Size(max = 100)
        String contentType
) {
}
