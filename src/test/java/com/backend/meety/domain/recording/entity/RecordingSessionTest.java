package com.backend.meety.domain.recording.entity;

import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.session;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.exception.RecordingException;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

class RecordingSessionTest {

    private RecordingSession session;

    @BeforeEach
    void setUp() {
        Team team = team();
        TeamMember member = member(team);
        Meeting meeting = meeting(team, member);
        session = session(meeting, member);
    }

    @Test
    @DisplayName("녹음 세션 생성 시 누적 일시정지 시간은 0이다")
    void startsRecordingWithFixedDeadline() {
        // 테스트 목적:
        // 녹음 세션이 처음 생성되면 누적 일시정지 시간이 0이고
        // 현재 일시정지 시각 없이 녹음 중 상태로 시작하는지 검증한다.

        // then
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.RECORDING);
        assertThat(session.getStartedAt()).isEqualTo(NOW);
        assertThat(session.getAutoEndAt()).isEqualTo(NOW.plusMinutes(90));
        assertThat(session.getPausedAt()).isNull();
        assertThat(session.getTotalPausedDurationMs()).isZero();
        assertThat(session.getEndedAt()).isNull();
    }

    @Test
    @DisplayName("일시정지 시점에는 누적 시간을 증가시키지 않고 재개 시 정지 시간을 누적한다")
    void pauseAndResumePreserveStartAndDeadlineAndClearCurrentPause() {
        // 테스트 목적:
        // RECORDING 상태에서 PAUSED로 전환할 때는 현재 정지 시작 시각만 저장하고
        // RECORDING으로 재개할 때 종료된 정지 구간 시간이 누적되는지 검증한다.

        // given
        session.pause(NOW.plusMinutes(10));

        // then
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.PAUSED);
        assertThat(session.getPausedAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(session.getTotalPausedDurationMs()).isZero();

        // when
        session.resume(NOW.plusMinutes(15));

        // then
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.RECORDING);
        assertThat(session.getPausedAt()).isNull();
        assertThat(session.getTotalPausedDurationMs()).isEqualTo(300_000L);
        assertThat(session.getStartedAt()).isEqualTo(NOW);
        assertThat(session.getAutoEndAt()).isEqualTo(NOW.plusMinutes(90));

        // when
        session.pause(NOW.plusMinutes(81));

        // then
        assertThat(session.getPausedAt()).isEqualTo(NOW.plusMinutes(81));
        assertThat(session.getTotalPausedDurationMs()).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("여러 번 일시정지와 재개를 반복하면 종료된 정지 구간 시간이 누적된다")
    void pauseAndResumeAccumulatesMultiplePausedDurations() {
        // 테스트 목적:
        // 두 번의 일시정지/재개 구간이 발생했을 때
        // 각 종료된 정지 구간 시간이 기존 누적 값에 더해지는지 검증한다.

        // given
        session.pause(NOW.plusMinutes(10));
        session.resume(NOW.plusMinutes(15));
        session.pause(NOW.plusMinutes(25));

        // when
        session.resume(NOW.plusMinutes(30));

        // then
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.RECORDING);
        assertThat(session.getPausedAt()).isNull();
        assertThat(session.getTotalPausedDurationMs()).isEqualTo(600_000L);
    }

    @Test
    void canResumeImmediatelyBeforeDeadline() {
        session.pause(NOW.plusMinutes(10));
        session.resume(NOW.plusMinutes(90).minusNanos(1));
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.RECORDING);
    }

    @ParameterizedTest
    @ValueSource(ints = {90, 91})
    void cannotResumeAtOrAfterDeadline(int minutes) {
        session.pause(NOW.plusMinutes(10));
        assertThatThrownBy(() -> session.resume(NOW.plusMinutes(minutes)))
                .isInstanceOf(RecordingException.class)
                .extracting("errorCode").isEqualTo(RecordingErrorCode.RECORDING_AUTO_END_REACHED);
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.PAUSED);
        assertThat(session.getPausedAt()).isEqualTo(NOW.plusMinutes(10));
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"RECORDING"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsPauseFromOtherStates(RecordingSessionStatus status) {
        ReflectionTestUtils.setField(session, "status", status);
        assertConflict(() -> session.pause(NOW));
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"PAUSED"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsResumeFromOtherStates(RecordingSessionStatus status) {
        ReflectionTestUtils.setField(session, "status", status);
        assertConflict(() -> session.resume(NOW));
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"RECORDING", "PAUSED"})
    void completesEitherActiveStateEvenAfterDeadline(RecordingSessionStatus status) {
        if (status == RecordingSessionStatus.PAUSED) {
            session.pause(NOW.plusMinutes(10));
        }
        session.complete(NOW.plusMinutes(100));
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.COMPLETED);
        assertThat(session.getEndedAt()).isEqualTo(NOW.plusMinutes(100));
        assertThat(session.getPausedAt()).isNull();
        assertThat(session.getStartedAt()).isEqualTo(NOW);
        assertThat(session.getAutoEndAt()).isEqualTo(NOW.plusMinutes(90));
        if (status == RecordingSessionStatus.PAUSED) {
            assertThat(session.getTotalPausedDurationMs()).isEqualTo(5_400_000L);
        } else {
            assertThat(session.getTotalPausedDurationMs()).isZero();
        }
    }

    @Test
    @DisplayName("PAUSED 상태에서 종료하면 마지막 일시정지 구간도 누적한다")
    void completeWhenPausedAccumulatesCurrentPausedDuration() {
        // 테스트 목적:
        // PAUSED 상태에서 재개 없이 바로 종료하는 경우
        // 아직 누적되지 않은 현재 일시정지 구간 시간이 누락되지 않는지 검증한다.

        // given
        session.pause(NOW.plusMinutes(10));

        // when
        session.complete(NOW.plusMinutes(15));

        // then
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.COMPLETED);
        assertThat(session.getPausedAt()).isNull();
        assertThat(session.getEndedAt()).isEqualTo(NOW.plusMinutes(15));
        assertThat(session.getTotalPausedDurationMs()).isEqualTo(300_000L);
    }

    @Test
    @DisplayName("RECORDING 상태에서 종료하면 추가 일시정지 시간을 누적하지 않는다")
    void completeWhenRecordingDoesNotAccumulatePausedDuration() {
        // 테스트 목적:
        // 현재 일시정지 중이 아닌 RECORDING 상태에서 종료하는 경우
        // 기존 누적 일시정지 시간이 변경되지 않는지 검증한다.

        // given
        session.pause(NOW.plusMinutes(10));
        session.resume(NOW.plusMinutes(15));

        // when
        session.complete(NOW.plusMinutes(20));

        // then
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.COMPLETED);
        assertThat(session.getPausedAt()).isNull();
        assertThat(session.getTotalPausedDurationMs()).isEqualTo(300_000L);
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"RECORDING", "PAUSED"}, mode = EnumSource.Mode.EXCLUDE)
    void rejectsCompleteFromInactiveState(RecordingSessionStatus status) {
        ReflectionTestUtils.setField(session, "status", status);
        assertConflict(() -> session.complete(NOW));
    }

    private void assertConflict(Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(RecordingException.class)
                .extracting("errorCode").isEqualTo(RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION);
    }
}
