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
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
    private final MeetingSseRegistry registry = new MeetingSseRegistry(new SimpleMeterRegistry());
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
    @DisplayName("회의 SSE 연결은 등록 후 CONNECTED 이벤트를 반환한다")
    void connectRegistersEmitterAndSendsConnectedEvent() {
        // 테스트 목적:
        // 회의 SSE 연결은 등록 후 CONNECTED 이벤트를 반환한다.

        // given
        TestSseEmitter emitter = emitterFactory.next();

        // when
        SseEmitter result = service.connect(USER_ID, MEETING_ID);

        // then
        assertThat(result).isSameAs(emitter);
        assertThat(registry.find(MEETING_ID, USER_ID)).contains(emitter);
        assertThat(emitter.sentData)
                .extracting(ResponseBodyEmitter.DataWithMediaType::getData)
                .contains(MeetingSseConnectedEvent.connected(MEETING_ID))
                .anyMatch(data -> data instanceof String value && value.startsWith("event:CONNECTED\n"));
    }

    @Test
    @DisplayName("회의에 참여하지 않은 사용자의 SSE 연결을 거부한다")
    void connectFailsBeforeParticipantJoined() {
        // 테스트 목적:
        // 회의에 참여하지 않은 사용자의 SSE 연결을 거부한다.

        // given
        when(meetingParticipantRepository.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                MEETING_ID, TEAM_MEMBER_ID, ParticipationStatus.JOINED)).thenReturn(false);

        // when, then
        assertCode(() -> service.connect(USER_ID, MEETING_ID), MeetingErrorCode.MEETING_PARTICIPANT_REQUIRED);

        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("ACTIVE 팀 멤버가 아닌 사용자의 SSE 연결을 거부한다")
    void connectFailsWhenUserIsNotActiveTeamMember() {
        // 테스트 목적:
        // ACTIVE 팀 멤버가 아닌 사용자의 SSE 연결을 거부한다.

        // given
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID, USER_ID, MembershipStatus.ACTIVE)).thenReturn(Optional.empty());

        // when, then
        assertCode(() -> service.connect(USER_ID, MEETING_ID), MeetingErrorCode.MEETING_ACCESS_DENIED);

        verify(meetingParticipantRepository, never())
                .existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(any(), any(), any());
    }

    @Test
    @DisplayName("존재하지 않는 회의의 SSE 연결을 거부한다")
    void connectFailsWhenMeetingDoesNotExist() {
        // 테스트 목적:
        // 존재하지 않는 회의의 SSE 연결을 거부한다.

        // given
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.empty());

        // when, then
        assertCode(() -> service.connect(USER_ID, MEETING_ID), MeetingErrorCode.MEETING_NOT_FOUND);

        verify(teamMemberRepository, never()).findByTeamIdAndUserIdAndMembershipStatus(any(), any(), any());
    }

    @Test
    @DisplayName("삭제된 회의의 SSE 연결을 거부한다")
    void connectFailsWhenMeetingWasDeleted() {
        // 테스트 목적:
        // 삭제된 회의의 SSE 연결을 거부한다.

        // given
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.empty());

        // when, then
        assertCode(() -> service.connect(USER_ID, MEETING_ID), MeetingErrorCode.MEETING_NOT_FOUND);

        assertThat(registry.countAll()).isZero();
    }

    @Test
    @DisplayName("재연결은 이전 emitter를 종료하고 새 연결을 유지한다")
    void duplicateConnectCompletesPreviousEmitterAndKeepsCurrentEmitter() {
        // 테스트 목적:
        // 재연결은 이전 emitter를 종료하고 새 연결을 유지한다.

        // given
        TestSseEmitter previous = emitterFactory.next();
        service.connect(USER_ID, MEETING_ID);
        TestSseEmitter current = emitterFactory.next();

        // when
        service.connect(USER_ID, MEETING_ID);

        // then
        assertThat(previous.completed).isTrue();
        assertThat(registry.find(MEETING_ID, USER_ID)).contains(current);
        assertThat(registry.count(MEETING_ID)).isOne();
    }

    @Test
    @DisplayName("완료·timeout·error 콜백은 해당 SSE 연결을 정리한다")
    void cleanupCallbacksRemoveEmitter() {
        // 테스트 목적:
        // 완료·timeout·error 콜백은 해당 SSE 연결을 정리한다.

        // given
        TestSseEmitter completion = emitterFactory.next();
        // when
        service.connect(USER_ID, MEETING_ID);
        completion.completion.run();
        // then
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
    @DisplayName("SSE 연결 정리는 회의 참가 상태를 변경하지 않는다")
    void cleanupDoesNotChangeParticipantState() {
        // 테스트 목적:
        // SSE 연결 정리는 회의 참가 상태를 변경하지 않는다.

        // given
        TestSseEmitter emitter = emitterFactory.next();
        MeetingParticipant participant = MeetingParticipant.create(meeting, teamMember);

        // when
        service.connect(USER_ID, MEETING_ID);
        emitter.completion.run();

        // then
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.JOINED);
        assertThat(participant.getDeletedAt()).isNull();
        verify(meetingParticipantRepository, never()).save(any());
        verify(meetingParticipantRepository, never()).delete(any());
    }

    @Test
    @DisplayName("이전 연결의 늦은 콜백은 재연결된 emitter를 제거하지 않는다")
    void removeStaleEmitterDoesNotRemoveNewEmitter() {
        // 테스트 목적:
        // 이전 연결의 늦은 콜백은 재연결된 emitter를 제거하지 않는다.

        // given
        TestSseEmitter stale = emitterFactory.next();
        service.connect(USER_ID, MEETING_ID);
        TestSseEmitter current = emitterFactory.next();
        service.connect(USER_ID, MEETING_ID);

        // when
        stale.completion.run();

        // then
        assertThat(registry.find(MEETING_ID, USER_ID)).contains(current);
    }

    @Test
    @DisplayName("최초 CONNECTED 전송 실패 시 연결을 오류로 종료하고 제거한다")
    void sendConnectedEventFailureRemovesEmitter() {
        // 테스트 목적:
        // 최초 CONNECTED 전송 실패 시 연결을 오류로 종료하고 제거한다.

        // given
        TestSseEmitter emitter = emitterFactory.next();
        emitter.sendFailure = new IOException("send failed");

        // when, then
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
