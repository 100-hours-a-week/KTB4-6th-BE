package com.backend.meety.domain.ai.messaging;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AiRequestPublishPropertiesTest {

    @Test
    @DisplayName("선점 만료 시간은 Confirm 대기 시간보다 길어야 한다")
    void requiresClaimTimeoutLongerThanConfirmTimeout() {
        assertThatThrownBy(() -> new AiRequestPublishProperties(20, Duration.ofSeconds(5), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiRequestPublishProperties(20, Duration.ofSeconds(5), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> new AiRequestPublishProperties(20, Duration.ofSeconds(5), Duration.ofSeconds(6)))
                .doesNotThrowAnyException();
    }
}
