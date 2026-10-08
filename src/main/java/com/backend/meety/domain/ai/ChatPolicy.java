package com.backend.meety.domain.ai;

import com.backend.meety.domain.ai.entity.AiRequestStatus;
import java.time.Duration;
import java.util.List;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class ChatPolicy {

    public static final Duration PROCESSING_TIMEOUT = Duration.ofSeconds(60);
    public static final int IDEMPOTENCY_KEY_MAX_LENGTH = 100;
    public static final List<AiRequestStatus> PROCESSING_STATUSES = List.of(
            AiRequestStatus.ACCEPTED, AiRequestStatus.PROCESSING
    );
}
