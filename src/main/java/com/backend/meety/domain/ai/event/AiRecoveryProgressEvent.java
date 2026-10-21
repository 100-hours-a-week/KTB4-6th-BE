package com.backend.meety.domain.ai.event;

public record AiRecoveryProgressEvent(Long recordingSessionId, String sessionStartRequestId) { }
