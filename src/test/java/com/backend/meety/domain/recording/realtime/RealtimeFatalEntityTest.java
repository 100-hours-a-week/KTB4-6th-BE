package com.backend.meety.domain.recording.realtime;

import static com.backend.meety.domain.recording.RecordingFixtures.*;
import static org.assertj.core.api.Assertions.*;

import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.util.ReflectionTestUtils;

class RealtimeFatalEntityTest {
    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"PREPARING", "RECORDING", "PAUSED"})
    @DisplayName("Fatal은 활성 녹음을 FAILED로 종료하고 일시정지 누적 시간과 종료 시각을 유지한다")
    void activeRecordingCanFail(RecordingSessionStatus state) {
        // 테스트 목적:
        // 사용자 상태와 무관하게 서버 실패를 확정하되 일시정지 경과시간을 잃지 않는지 검증한다.
        // given
        var team = team(); var member = member(team); var session = session(meeting(team, member), member);
        if (state == RecordingSessionStatus.PAUSED) { session.pause(NOW); }
        else { ReflectionTestUtils.setField(session, "status", state); }
        // when
        session.fail(NOW.plusSeconds(2));
        // then
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.FAILED);
        assertThat(session.getEndedAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(session.getPausedAt()).isNull();
        assertThat(session.getTotalPausedDurationMs()).isEqualTo(state == RecordingSessionStatus.PAUSED ? 2000 : 0);
        assertThatThrownBy(() -> session.complete(NOW.plusSeconds(3)))
                .isInstanceOf(com.backend.meety.domain.recording.exception.RecordingException.class);
    }
}
