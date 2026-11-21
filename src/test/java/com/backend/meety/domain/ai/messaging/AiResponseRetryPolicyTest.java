package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiResponseRetryPolicyTest {

    @Test
    @DisplayName("재시도 대기 시간은 30초에서 시작해 4배씩 늘어난다")
    void backoffGrowsExponentially() {
        assertThat(AiResponseRetryPolicy.backoff(0)).isEqualTo(Duration.ofSeconds(30));
        assertThat(AiResponseRetryPolicy.backoff(1)).isEqualTo(Duration.ofSeconds(120));
        assertThat(AiResponseRetryPolicy.backoff(2)).isEqualTo(Duration.ofSeconds(480));
    }
}
