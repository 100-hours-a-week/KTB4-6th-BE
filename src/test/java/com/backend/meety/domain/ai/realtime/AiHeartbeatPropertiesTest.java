package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

class AiHeartbeatPropertiesTest {
    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    @DisplayName("Heartbeat interval과 pong timeout은 양수여야 한다")
    void positiveDurations(long seconds) {
        // 테스트 목적:
        // 동작할 수 없거나 busy loop를 만드는 시간 설정을 시작 시 거부하는지 검증한다.
        // given
        Duration invalid = Duration.ofSeconds(seconds);
        Duration valid = Duration.ofSeconds(5);
        // when, then
        assertThatThrownBy(() -> new AiHeartbeatProperties(invalid, valid, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiHeartbeatProperties(valid, invalid, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiHeartbeatProperties(null, valid, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AiHeartbeatProperties(valid, null, 2)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    @DisplayName("Heartbeat 연속 실패 기준은 양수여야 한다")
    void positiveThreshold(int threshold) {
        // 테스트 목적:
        // 실패 없이 장애가 되는 잘못된 threshold를 거부하는지 검증한다.
        // given
        var duration = Duration.ofSeconds(5);
        // when, then
        assertThatThrownBy(() -> new AiHeartbeatProperties(duration, duration, threshold))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("Heartbeat property는 Duration과 연속 실패 수로 바인딩된다")
    void bindsProperties() {
        // 테스트 목적:
        // 운영 환경에서 코드 수정 없이 감시 정책을 조정할 수 있는지 검증한다.
        // given
        var environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test", java.util.Map.of(
                "ai.heartbeat.interval", "5s", "ai.heartbeat.pong-timeout", "5s", "ai.heartbeat.failure-threshold", "2")));
        // when
        var properties = Binder.get(environment).bind("ai.heartbeat", Bindable.of(AiHeartbeatProperties.class)).get();
        // then
        assertThat(properties).isEqualTo(new AiHeartbeatProperties(Duration.ofSeconds(5), Duration.ofSeconds(5), 2));
    }
}
