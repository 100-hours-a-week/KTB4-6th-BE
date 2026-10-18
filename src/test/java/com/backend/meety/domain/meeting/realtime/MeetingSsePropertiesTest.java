package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.meety.global.config.MeetingSseExecutorProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MeetingSsePropertiesTest {

    @ParameterizedTest
    @CsvSource({"0, 4, 100, 10", "4, 3, 100, 10", "4, 4, -1, 10", "4, 4, 100, -1"})
    @DisplayName("worker·대기열·종료 대기 설정이 잘못되면 시작 시점에 거절한다")
    void rejectsInvalidExecutorSizing(int core, int maximum, int capacity, int shutdownWait) {
        // 테스트 목적:
        // worker가 없거나 무효한 한도로 실행 격리와 bounded 대기열을 구성하지 못하는 설정은
        // 서비스 시작 전에 명확하게 거절되는지 검증한다.

        // given

        // when, then
        assertThatThrownBy(() -> new MeetingSseExecutorProperties(core, maximum, capacity, shutdownWait))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0, 1000", "-1, 1000", "100, 0", "100, 99"})
    @DisplayName("회의별·전체 pending 한도가 양수가 아니거나 서로 모순되면 시작 시점에 거절한다")
    void rejectsInvalidPendingLimits(int perMeeting, int total) {
        // 테스트 목적:
        // 회의 큐와 전체 이벤트 수의 제한을 보장할 수 없는 설정이
        // 무한 큐나 모순된 용량으로 사용되지 않는지 검증한다.

        // given

        // when, then
        assertThatThrownBy(() -> new MeetingSseQueueProperties(perMeeting, total))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
