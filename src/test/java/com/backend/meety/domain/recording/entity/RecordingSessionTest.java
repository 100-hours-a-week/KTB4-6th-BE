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
    void startsRecordingWithFixedDeadline() {
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.RECORDING);
        assertThat(session.getStartedAt()).isEqualTo(NOW);
        assertThat(session.getAutoEndAt()).isEqualTo(NOW.plusMinutes(90));
        assertThat(session.getPausedAt()).isNull();
        assertThat(session.getEndedAt()).isNull();
    }

    @Test
    void pauseAndResumePreserveStartAndDeadlineAndClearCurrentPause() {
        session.pause(NOW.plusMinutes(10));
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.PAUSED);
        assertThat(session.getPausedAt()).isEqualTo(NOW.plusMinutes(10));
        session.resume(NOW.plusMinutes(80));
        assertThat(session.getStatus()).isEqualTo(RecordingSessionStatus.RECORDING);
        assertThat(session.getPausedAt()).isNull();
        assertThat(session.getStartedAt()).isEqualTo(NOW);
        assertThat(session.getAutoEndAt()).isEqualTo(NOW.plusMinutes(90));
        session.pause(NOW.plusMinutes(81));
        assertThat(session.getPausedAt()).isEqualTo(NOW.plusMinutes(81));
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
