package com.backend.meety.domain.user.dto;

import java.time.LocalDateTime;

public record UserVoiceConsentResponse(LocalDateTime consentedAt) {
}
