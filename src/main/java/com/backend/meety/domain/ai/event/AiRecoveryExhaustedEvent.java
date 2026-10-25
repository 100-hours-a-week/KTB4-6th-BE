package com.backend.meety.domain.ai.event;

/** #317 integration point. This event does not itself terminate recording or change DB status. */
public record AiRecoveryExhaustedEvent(Long recordingSessionId, String recoveryGeneration,
                                       String owner, String sessionStartRequestId,
                                       int attempts, String reason) { }
