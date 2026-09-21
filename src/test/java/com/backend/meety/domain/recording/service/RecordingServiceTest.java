package com.backend.meety.domain.recording.service;

import static com.backend.meety.domain.recording.RecordingFixtures.CLOCK;
import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.credit;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.session;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditSourceType;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.recording.dto.RecordingSessionResponse;
import com.backend.meety.domain.recording.entity.RecordingSession;
import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.backend.meety.domain.recording.exception.RecordingErrorCode;
import com.backend.meety.domain.recording.repository.RecordingSessionRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.global.exception.BaseCode;
import com.backend.meety.global.exception.BusinessException;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

class RecordingServiceTest {

    private static final List<RecordingSessionStatus> ACTIVE = List.of(
            RecordingSessionStatus.RECORDING, RecordingSessionStatus.PAUSED);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final MeetingParticipantRepository participants = mock(MeetingParticipantRepository.class);
    private final RecordingSessionRepository recordings = mock(RecordingSessionRepository.class);
    private final TeamCreditRepository credits = mock(TeamCreditRepository.class);
    private final CreditLedgerRepository ledgers = mock(CreditLedgerRepository.class);
    private final RecordingService service = new RecordingService(
            meetings, members, participants, recordings, credits, ledgers, CLOCK);
    private Team team;
    private TeamMember member;
    private Meeting meeting;
    private TeamCredit credit;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
        meeting = meeting(team, member);
        credit = credit(team, 20L);
        when(meetings.findByIdForUpdateAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));
    }

    private void allowStart() {
        when(participants.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                100L, 10L, ParticipationStatus.JOINED)).thenReturn(true);
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(credit));
        when(recordings.save(any(RecordingSession.class))).thenAnswer(call -> withId(call.getArgument(0), 700L));
    }

    @Test
    void startChargesExactlyTwentyAndStartsMeetingAtSameTime() {
        allowStart();
        RecordingSessionResponse response = service.start(1L, 100L);
        assertThat(response.recordingSessionId()).isEqualTo(700L);
        assertThat(response.meetingId()).isEqualTo(100L);
        assertThat(response.startedByTeamMemberId()).isEqualTo(10L);
        assertThat(response.status()).isEqualTo(RecordingSessionStatus.RECORDING);
        assertThat(response.startedAt()).isEqualTo(NOW);
        assertThat(response.autoEndAt()).isEqualTo(NOW.plusMinutes(90));
        assertThat(credit.getBalance()).isZero();
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);
        assertThat(meeting.getStartedAt()).isEqualTo(response.startedAt());

        ArgumentCaptor<CreditLedger> ledger = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers).save(ledger.capture());
        assertThat(ledger.getValue().getAmount()).isEqualTo(-20L);
        assertThat(ledger.getValue().getBalanceAfter()).isZero();
        assertThat(ledger.getValue().getType()).isEqualTo(CreditTransactionType.USE);
        assertThat(ledger.getValue().getSourceType()).isEqualTo(CreditSourceType.RECORDING);
        assertThat(ledger.getValue().getSourceId()).isEqualTo(700L);
        assertThat(ledger.getValue().getIdempotencyKey()).isEqualTo("USE:RECORDING:700");
        InOrder order = inOrder(meetings, credits, recordings, ledgers);
        order.verify(meetings).findByIdForUpdateAndDeletedAtIsNull(100L);
        order.verify(credits).findByTeamIdForUpdate(2L);
        order.verify(recordings).save(any());
        order.verify(ledgers).save(any());
    }

    @Test
    void startRejectsMissingOrDeletedMeeting() {
        when(meetings.findByIdForUpdateAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());
        assertCode(() -> service.start(1L, 100L), MeetingErrorCode.MEETING_NOT_FOUND);
        verifyNoInteractions(credits, ledgers);
    }

    @Test
    void startRejectsNonActiveMember() {
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());
        assertCode(() -> service.start(1L, 100L), MeetingErrorCode.MEETING_ACCESS_DENIED);
        verifyNoInteractions(participants, credits, ledgers);
    }

    @Test
    void startRequiresJoinedNonDeletedParticipant() {
        assertCode(() -> service.start(1L, 100L), MeetingErrorCode.MEETING_PARTICIPANT_REQUIRED);
        verify(participants).existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                100L, 10L, ParticipationStatus.JOINED);
        verifyNoInteractions(credits, ledgers);
    }

    @Test
    void startRejectsInProgressMeetingEvenWithoutSession() {
        meeting.start(NOW);
        assertCode(() -> service.start(1L, 100L), RecordingErrorCode.RECORDING_ALREADY_ACTIVE);
        verifyNoInteractions(credits, ledgers);
    }

    @Test
    void startRejectsCompletedMeeting() {
        meeting.start(NOW);
        meeting.complete(NOW);
        assertCode(() -> service.start(1L, 100L), MeetingErrorCode.MEETING_COMPLETED);
    }

    @Test
    void startRejectsExistingActiveSession() {
        allowStart();
        when(recordings.existsByMeetingIdAndStatusInAndDeletedAtIsNull(100L, ACTIVE)).thenReturn(true);
        assertCode(() -> service.start(1L, 100L), RecordingErrorCode.RECORDING_ALREADY_ACTIVE);
        verifyNoInteractions(credits, ledgers);
    }

    @Test
    void startRejectsInsufficientCreditBeforeCreatingSession() {
        allowStart();
        credit.use(1L);
        assertCode(() -> service.start(1L, 100L), CreditErrorCode.INSUFFICIENT_CREDIT);
        verify(recordings, never()).save(any());
        verifyNoInteractions(ledgers);
        assertThat(credit.getBalance()).isEqualTo(19L);
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.WAITING);
    }

    @Test
    void missingCreditIsServerErrorAndDoesNotCreateCredit() {
        allowStart();
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.empty());
        assertCode(() -> service.start(1L, 100L), CreditErrorCode.TEAM_CREDIT_NOT_FOUND);
        verify(credits, never()).save(any());
        verify(recordings, never()).save(any());
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"RECORDING", "PAUSED"})
    void getActiveReturnsEitherActiveStatusWithoutParticipantOrLock(RecordingSessionStatus status) {
        RecordingSession session = session(meeting, member);
        if (status == RecordingSessionStatus.PAUSED) {
            session.pause(NOW);
        }
        allowRead();
        when(recordings.findByMeetingIdAndStatusInAndDeletedAtIsNull(100L, ACTIVE))
                .thenReturn(Optional.of(session));
        assertThat(service.getActive(1L, 100L).status()).isEqualTo(status);
        verify(meetings, never()).findByIdForUpdateAndDeletedAtIsNull(any());
        verifyNoInteractions(participants, credits, ledgers);
    }

    @Test
    void getActiveWithoutSessionReturnsNotFound() {
        allowRead();
        assertCode(() -> service.getActive(1L, 100L), RecordingErrorCode.RECORDING_SESSION_NOT_FOUND);
        verify(recordings).findByMeetingIdAndStatusInAndDeletedAtIsNull(100L, ACTIVE);
    }

    @Test
    void getActiveRejectsOtherTeam() {
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        assertCode(() -> service.getActive(1L, 100L), MeetingErrorCode.MEETING_ACCESS_DENIED);
        verifyNoInteractions(recordings);
    }

    @Test
    void getActiveRejectsMissingMeeting() {
        assertCode(() -> service.getActive(1L, 100L), MeetingErrorCode.MEETING_NOT_FOUND);
    }

    @Test
    void pauseLocksMeetingBeforeSessionAndKeepsMeetingInProgress() {
        RecordingSession session = allowPatch();
        RecordingSessionResponse response = service.updateStatus(1L, 700L, RecordingSessionStatus.PAUSED);
        assertThat(response.status()).isEqualTo(RecordingSessionStatus.PAUSED);
        assertThat(response.pausedAt()).isEqualTo(NOW);
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);
        assertThat(session.getEndedAt()).isNull();
        InOrder order = inOrder(recordings, meetings, members);
        order.verify(recordings).findMeetingIdByIdAndDeletedAtIsNull(700L);
        order.verify(meetings).findByIdForUpdateAndDeletedAtIsNull(100L);
        order.verify(recordings).findByIdForUpdateAndDeletedAtIsNull(700L);
        order.verify(members).findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE);
    }

    @Test
    void resumeClearsPausedAtWithoutChangingStart() {
        RecordingSession session = allowPatch();
        session.pause(NOW);
        RecordingSessionResponse response = service.updateStatus(1L, 700L, RecordingSessionStatus.RECORDING);
        assertThat(response.startedAt()).isEqualTo(NOW);
        assertThat(response.pausedAt()).isNull();
        assertThat(response.autoEndAt()).isEqualTo(NOW.plusMinutes(90));
    }

    @Test
    void resumeAtDeadlineIsRejectedUsingServiceClock() {
        allowPatch();
        RecordingSession expired = withId(RecordingSession.start(meeting, member, NOW.minusMinutes(90)), 700L);
        expired.pause(NOW.minusMinutes(5));
        when(recordings.findByIdForUpdateAndDeletedAtIsNull(700L)).thenReturn(Optional.of(expired));
        assertCode(() -> service.updateStatus(1L, 700L, RecordingSessionStatus.RECORDING),
                RecordingErrorCode.RECORDING_AUTO_END_REACHED);
        assertThat(expired.getStatus()).isEqualTo(RecordingSessionStatus.PAUSED);
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.IN_PROGRESS);
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"RECORDING", "PAUSED"})
    void completeEndsMeetingAndSessionAtSameInstant(RecordingSessionStatus initial) {
        RecordingSession session = allowPatch();
        if (initial == RecordingSessionStatus.PAUSED) {
            session.pause(NOW);
        }
        RecordingSessionResponse response = service.updateStatus(1L, 700L, RecordingSessionStatus.COMPLETED);
        assertThat(response.status()).isEqualTo(RecordingSessionStatus.COMPLETED);
        assertThat(response.endedAt()).isEqualTo(NOW);
        assertThat(meeting.getStatus()).isEqualTo(MeetingStatus.COMPLETED);
        assertThat(meeting.getEndedAt()).isEqualTo(response.endedAt());
        verifyNoInteractions(credits, ledgers);
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"PAUSED", "RECORDING", "COMPLETED"})
    void rejectsOtherOwnerForEveryOperation(RecordingSessionStatus target) {
        allowPatch();
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(withId(member(team), 11L)));
        assertCode(() -> service.updateStatus(1L, 700L, target), RecordingErrorCode.RECORDING_OWNER_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"PAUSED", "RECORDING", "COMPLETED"})
    void rejectsFormerOwnerWithoutActiveMembership(RecordingSessionStatus target) {
        allowPatch();
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());
        assertCode(() -> service.updateStatus(1L, 700L, target), RecordingErrorCode.RECORDING_OWNER_REQUIRED);
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"PAUSED", "RECORDING", "COMPLETED"})
    void rejectsAllTransitionsAfterCompleted(RecordingSessionStatus target) {
        RecordingSession session = allowPatch();
        session.complete(NOW);
        meeting.complete(NOW);
        assertCode(() -> service.updateStatus(1L, 700L, target), RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION);
    }

    @Test
    void rejectsRepeatedPause() {
        allowPatch().pause(NOW);
        assertCode(() -> service.updateStatus(1L, 700L, RecordingSessionStatus.PAUSED),
                RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION);
    }

    @Test
    void rejectsResumeWhileRecording() {
        allowPatch();
        assertCode(() -> service.updateStatus(1L, 700L, RecordingSessionStatus.RECORDING),
                RecordingErrorCode.INVALID_RECORDING_STATUS_TRANSITION);
    }

    @Test
    void rejectsMissingSession() {
        assertCode(() -> service.updateStatus(1L, 700L, RecordingSessionStatus.PAUSED),
                RecordingErrorCode.RECORDING_SESSION_NOT_FOUND);
    }

    @Test
    void checksSessionAgainAfterMeetingLock() {
        allowPatch();
        when(recordings.findByIdForUpdateAndDeletedAtIsNull(700L)).thenReturn(Optional.empty());
        assertCode(() -> service.updateStatus(1L, 700L, RecordingSessionStatus.PAUSED),
                RecordingErrorCode.RECORDING_SESSION_NOT_FOUND);
    }

    @ParameterizedTest
    @EnumSource(value = RecordingSessionStatus.class, names = {"PREPARING", "FAILED"})
    void rejectsUnsupportedStatuses(RecordingSessionStatus target) {
        assertCode(() -> service.updateStatus(1L, 700L, target), RecordingErrorCode.INVALID_RECORDING_STATUS);
        verifyNoInteractions(recordings);
    }

    private void allowRead() {
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(members.existsByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE)).thenReturn(true);
    }

    private RecordingSession allowPatch() {
        meeting.start(NOW);
        RecordingSession session = session(meeting, member);
        when(recordings.findMeetingIdByIdAndDeletedAtIsNull(700L)).thenReturn(Optional.of(100L));
        when(recordings.findByIdForUpdateAndDeletedAtIsNull(700L)).thenReturn(Optional.of(session));
        return session;
    }

    private void assertCode(Runnable action, BaseCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }
}
