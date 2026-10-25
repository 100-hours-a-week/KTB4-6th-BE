package com.backend.meety.domain.ai.event;

public record AiRecoverableFailureEvent(Long recordingSessionId, String sessionStartRequestId, String reason) { }
