package com.backend.meety.domain.ai.realtime;

import java.time.Duration;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class AiLiveMeetingPolicy {

    public static final Duration READY_TIMEOUT = Duration.ofSeconds(5);

    public static final Duration STOP_TIMEOUT = Duration.ofSeconds(30);
}
