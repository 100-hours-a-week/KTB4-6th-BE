package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AiAudioAckPropertiesTest {
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("Audio ACK timeout과 poll 주기는 양수여야 한다")
    void durationsMustBePositive(long seconds) {
        // 테스트 목적:
        // 감시가 동작하지 않거나 무한 반복할 수 있는 잘못된 설정을 시작 시 거부하는지 검증한다.
        // given
        Duration invalid = Duration.ofSeconds(seconds);
        Duration valid = Duration.ofSeconds(1);
        // when, then
        assertThatThrownBy(() -> new AiAudioAckProperties(invalid, valid, 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiAudioAckProperties(valid, invalid, 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("Audio ACK pending 상한은 양수여야 한다")
    void capacityMustBePositive(int capacity) {
        // 테스트 목적:
        // ACK 상태를 저장할 수 없는 상한 설정을 시작 시 거부하는지 검증한다.
        // given
        Duration duration = Duration.ofSeconds(1);
        // when, then
        assertThatThrownBy(() -> new AiAudioAckProperties(duration, duration, capacity))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
