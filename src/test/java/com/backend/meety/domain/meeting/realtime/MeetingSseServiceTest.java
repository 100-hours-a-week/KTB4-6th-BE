package com.backend.meety.domain.meeting.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.global.exception.BusinessException;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyEmitter;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class MeetingSseServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 2L;
    private static final Long MEETING_ID = 100L;
    private static final Long TEAM_MEMBER_ID = 10L;

    private final MeetingRepository meetingRepository = mock(MeetingRepository.class);
    private final TeamMemberRepository teamMemberRepository = mock(TeamMemberRepository.class);
    private final MeetingParticipantRepository meetingParticipantRepository =
            mock(MeetingParticipantRepository.class);
    private final MeetingSseRegistry registry = new MeetingSseRegistry();
    private final TestMeetingSseEmitterFactory emitterFactory = new TestMeetingSseEmitterFactory();
    private final MeetingSseService service = new MeetingSseService(
            meetingRepository,
            teamMemberRepository,
            meetingParticipantRepository,
            registry,
            emitterFactory
    );

    private Team team;
    private TeamMember teamMember;
    private Meeting meeting;

    @BeforeEach
    void setUp() {
        team = team();
        teamMember = teamMember(team);
        meeting = meeting(team, teamMember);
        allowSseConnect();
    }

    @Test
    void connectRegistersEmitterAndSendsConnectedEvent() {
        TestSseEmitter emitter = emitterFactory.next();

        SseEmitter result = service.connect(USER_ID, MEETING_ID);

        assertThat(result).isSameAs(emitter);
        assertThat(registry.find(MEETING_ID, USER_ID)).contains(emitter);
        assertThat(emitter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseConnectedEvent.connected(MEETING_ID))
                .anyMatch(data -> data instanceof String value && value.startsWith("event:CONNECTED\n"));
    }

    @Test
    void connectFailsBeforeParticipantJoined() {
        when(meetingParticipantRepository.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                MEETING_ID, TEAM_MEMBER_ID, ParticipationStatus.JOINED)).thenReturn(false);

        assertCode(() -> service.connect(USER_ID, MEETING_ID), MeetingErrorCode.MEETING_PARTICIPANT_REQUIRED);

        assertThat(registry.countAll()).isZero();
    }

    @Test
    void connectFailsWhenUserIsNotActiveTeamMember() {
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID, USER_ID, MembershipStatus.ACTIVE)).thenReturn(Optional.empty());

        assertCode(() -> service.connect(USER_ID, MEETING_ID), MeetingErrorCode.MEETING_ACCESS_DENIED);

        verify(meetingParticipantRepository, never())
                .existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(any(), any(), any());
    }

    @Test
    void connectFailsWhenMeetingDoesNotExist() {
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.empty());

        assertCode(() -> service.connect(USER_ID, MEETING_ID), MeetingErrorCode.MEETING_NOT_FOUND);

        verify(teamMemberRepository, never()).findByTeamIdAndUserIdAndMembershipStatus(any(), any(), any());
    }

    @Test
    void duplicateConnectCompletesPreviousEmitterAndKeepsCurrentEmitter() {
        TestSseEmitter previous = emitterFactory.next();
        service.connect(USER_ID, MEETING_ID);
        TestSseEmitter current = emitterFactory.next();

        service.connect(USER_ID, MEETING_ID);

        assertThat(previous.completed).isTrue();
        assertThat(registry.find(MEETING_ID, USER_ID)).contains(current);
        assertThat(registry.count(MEETING_ID)).isOne();
    }

    @Test
    void cleanupCallbacksRemoveEmitter() {
        TestSseEmitter completion = emitterFactory.next();
        service.connect(USER_ID, MEETING_ID);
        completion.completion.run();
        assertThat(registry.find(MEETING_ID, USER_ID)).isEmpty();

        TestSseEmitter timeout = emitterFactory.next();
        service.connect(USER_ID, MEETING_ID);
        timeout.timeout.run();
        assertThat(registry.find(MEETING_ID, USER_ID)).isEmpty();

        TestSseEmitter error = emitterFactory.next();
        service.connect(USER_ID, MEETING_ID);
        error.error.accept(new IOException("network"));
        assertThat(registry.find(MEETING_ID, USER_ID)).isEmpty();
    }

    @Test
    void cleanupDoesNotChangeParticipantState() {
        TestSseEmitter emitter = emitterFactory.next();
        MeetingParticipant participant = MeetingParticipant.create(meeting, teamMember);

        service.connect(USER_ID, MEETING_ID);
        emitter.completion.run();

        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.JOINED);
        assertThat(participant.getDeletedAt()).isNull();
        verify(meetingParticipantRepository, never()).save(any());
        verify(meetingParticipantRepository, never()).delete(any());
    }

    @Test
    void removeStaleEmitterDoesNotRemoveNewEmitter() {
        TestSseEmitter stale = emitterFactory.next();
        service.connect(USER_ID, MEETING_ID);
        TestSseEmitter current = emitterFactory.next();
        service.connect(USER_ID, MEETING_ID);

        stale.completion.run();

        assertThat(registry.find(MEETING_ID, USER_ID)).contains(current);
    }

    @Test
    void sendConnectedEventFailureRemovesEmitter() {
        TestSseEmitter emitter = emitterFactory.next();
        emitter.sendFailure = new IOException("send failed");

        assertThatThrownBy(() -> service.connect(USER_ID, MEETING_ID))
                .isInstanceOf(IllegalStateException.class);

        assertThat(emitter.completedWithError).isTrue();
        assertThat(registry.find(MEETING_ID, USER_ID)).isEmpty();
    }

    private void allowSseConnect() {
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID, USER_ID, MembershipStatus.ACTIVE)).thenReturn(Optional.of(teamMember));
        when(meetingParticipantRepository.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                MEETING_ID, TEAM_MEMBER_ID, ParticipationStatus.JOINED)).thenReturn(true);
    }

    private Team team() {
        Team team = Team.create("SSE 테스트");
        ReflectionTestUtils.setField(team, "id", TEAM_ID);
        return team;
    }

    private TeamMember teamMember(Team team) {
        TeamMember teamMember = TeamMember.createLeader(mock(com.backend.meety.domain.user.entity.User.class),
                team, "참여자");
        ReflectionTestUtils.setField(teamMember, "id", TEAM_MEMBER_ID);
        return teamMember;
    }

    private Meeting meeting(Team team, TeamMember teamMember) {
        Meeting meeting = Meeting.create(team, teamMember, "회의", "목적", null,
                LocalDateTime.of(2026, 9, 21, 20, 0), 30);
        ReflectionTestUtils.setField(meeting, "id", MEETING_ID);
        ReflectionTestUtils.setField(meeting, "status", MeetingStatus.IN_PROGRESS);
        return meeting;
    }

    private void assertCode(Runnable action, MeetingErrorCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }

    private static class TestMeetingSseEmitterFactory extends MeetingSseEmitterFactory {

        private TestSseEmitter next;

        TestSseEmitter next() {
            next = new TestSseEmitter();
            return next;
        }

        @Override
        public SseEmitter create() {
            return next;
        }
    }

    private static class TestSseEmitter extends SseEmitter {

        private Runnable completion;
        private Runnable timeout;
        private Consumer<Throwable> error;
        private boolean completed;
        private boolean completedWithError;
        private IOException sendFailure;
        private Set<ResponseBodyEmitter.DataWithMediaType> sentData;

        @Override
        public void send(SseEventBuilder builder) throws IOException {
            if (sendFailure != null) {
                throw sendFailure;
            }
            sentData = builder.build();
        }

        @Override
        public void complete() {
            completed = true;
        }

        @Override
        public void completeWithError(Throwable ex) {
            completedWithError = true;
        }

        @Override
        public void onCompletion(Runnable callback) {
            completion = callback;
        }

        @Override
        public void onTimeout(Runnable callback) {
            timeout = callback;
        }

        @Override
        public void onError(Consumer<Throwable> callback) {
            error = callback;
        }
    }
}
