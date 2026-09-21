package com.backend.meety.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.meeting.dto.MeetingParticipantListResponse;
import com.backend.meety.domain.meeting.dto.MeetingParticipantResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import com.backend.meety.domain.meeting.event.MeetingParticipantLeftEvent;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

class MeetingParticipantServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 2L;
    private static final Long MEETING_ID = 3L;
    private static final Long TEAM_MEMBER_ID = 4L;
    private static final Long PARTICIPANT_ID = 5L;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-15T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    private MeetingRepository meetingRepository;
    private TeamMemberRepository teamMemberRepository;
    private MeetingParticipantRepository meetingParticipantRepository;
    private ApplicationEventPublisher eventPublisher;
    private MeetingParticipantService meetingParticipantService;

    @BeforeEach
    void setUp() {
        meetingRepository = org.mockito.Mockito.mock(MeetingRepository.class);
        teamMemberRepository = org.mockito.Mockito.mock(TeamMemberRepository.class);
        meetingParticipantRepository = org.mockito.Mockito.mock(MeetingParticipantRepository.class);
        eventPublisher = org.mockito.Mockito.mock(ApplicationEventPublisher.class);
        meetingParticipantService = new MeetingParticipantService(
                meetingRepository,
                teamMemberRepository,
                meetingParticipantRepository,
                FIXED_CLOCK,
                eventPublisher
        );
    }

    @Test
    @DisplayName("WAITING 회의에 새 참석자로 참여한다")
    void joinWaitingMeeting() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.empty());
        when(meetingParticipantRepository.save(any(MeetingParticipant.class))).thenAnswer(invocation -> {
            MeetingParticipant participant = invocation.getArgument(0);
            ReflectionTestUtils.setField(participant, "id", PARTICIPANT_ID);
            ReflectionTestUtils.setField(participant, "createdAt", LocalDateTime.of(2026, 9, 15, 12, 0));
            return participant;
        });

        MeetingParticipantResponse response = meetingParticipantService.joinMeeting(USER_ID, MEETING_ID);

        assertThat(response.participantId()).isEqualTo(PARTICIPANT_ID);
        assertThat(response.teamMemberId()).isEqualTo(TEAM_MEMBER_ID);
        assertThat(response.displayName()).isEqualTo("홍길동");
        assertThat(response.participationStatus()).isEqualTo(ParticipationStatus.JOINED);
        ArgumentCaptor<MeetingParticipant> participantCaptor = ArgumentCaptor.forClass(MeetingParticipant.class);
        verify(meetingParticipantRepository).save(participantCaptor.capture());
        assertThat(participantCaptor.getValue().getParticipationStatus()).isEqualTo(ParticipationStatus.JOINED);
        assertThat(participantCaptor.getValue().getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("IN_PROGRESS 회의에 참여할 수 있다")
    void joinInProgressMeeting() {
        Meeting meeting = meeting(MeetingStatus.IN_PROGRESS);
        TeamMember teamMember = teamMember("홍길동");
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.empty());
        when(meetingParticipantRepository.save(any(MeetingParticipant.class))).thenAnswer(invocation -> invocation.getArgument(0));

        MeetingParticipantResponse response = meetingParticipantService.joinMeeting(USER_ID, MEETING_ID);

        assertThat(response.participationStatus()).isEqualTo(ParticipationStatus.JOINED);
    }

    @Test
    @DisplayName("COMPLETED 회의에는 참여할 수 없다")
    void joinCompletedMeetingFails() {
        Meeting meeting = meeting(MeetingStatus.COMPLETED);
        TeamMember teamMember = teamMember("홍길동");
        setUpMeetingAndActiveTeamMember(meeting, teamMember);

        assertThatThrownBy(() -> meetingParticipantService.joinMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_PARTICIPATION_NOT_ALLOWED);

        verify(meetingParticipantRepository, never()).findByMeetingIdAndTeamMemberId(any(), any());
        verify(meetingParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("회의가 없거나 삭제된 회의이면 참여할 수 없다")
    void joinMeetingNotFound() {
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingParticipantService.joinMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_NOT_FOUND);

        verify(teamMemberRepository, never()).findByTeamIdAndUserIdAndMembershipStatus(any(), any(), any());
    }

    @Test
    @DisplayName("활성 팀원이 아니면 참여할 수 없다")
    void joinMeetingWithoutActiveTeamMember() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingParticipantService.joinMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_ACCESS_DENIED);

        verify(meetingParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("이미 JOINED 상태이면 중복 참여 409 예외를 던진다")
    void duplicateJoinFails() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        MeetingParticipant participant = participant(meeting, teamMember, ParticipationStatus.JOINED, null);
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> meetingParticipantService.joinMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.ALREADY_PARTICIPATING);

        verify(meetingParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("LEFT 상태이면 기존 row를 재사용해 재참여한다")
    void rejoinLeftParticipant() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        MeetingParticipant participant = participant(
                meeting,
                teamMember,
                ParticipationStatus.LEFT,
                LocalDateTime.of(2026, 9, 15, 11, 0)
        );
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.of(participant));

        MeetingParticipantResponse response = meetingParticipantService.joinMeeting(USER_ID, MEETING_ID);

        assertThat(response.participantId()).isEqualTo(PARTICIPANT_ID);
        assertThat(response.participationStatus()).isEqualTo(ParticipationStatus.JOINED);
        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.JOINED);
        assertThat(participant.getDeletedAt()).isNull();
        verify(meetingParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("DISCONNECTED 상태이면 참여 상태 충돌 예외를 던진다")
    void joinDisconnectedParticipantFails() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        MeetingParticipant participant = participant(meeting, teamMember, ParticipationStatus.DISCONNECTED, null);
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> meetingParticipantService.joinMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.PARTICIPANT_STATUS_CONFLICT);

        verify(meetingParticipantRepository, never()).save(any());
    }

    @Test
    @DisplayName("JOINED 상태이지만 deletedAt이 있으면 참여 상태 충돌 예외를 던진다")
    void joinSoftDeletedJoinedParticipantFails() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        MeetingParticipant participant = participant(
                meeting,
                teamMember,
                ParticipationStatus.JOINED,
                LocalDateTime.of(2026, 9, 15, 11, 0)
        );
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> meetingParticipantService.joinMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.PARTICIPANT_STATUS_CONFLICT);

        assertThat(participant.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 11, 0));
    }

    @Test
    @DisplayName("현재 JOINED 참석자 목록을 조회한다")
    void getParticipants() {
        Meeting meeting = meeting(MeetingStatus.COMPLETED);
        TeamMember requestMember = teamMember("요청자");
        TeamMember firstMember = teamMember(10L, "김회의");
        TeamMember secondMember = teamMember(11L, "이기록");
        MeetingParticipant first = participant(100L, meeting, firstMember, ParticipationStatus.JOINED, null);
        MeetingParticipant second = participant(101L, meeting, secondMember, ParticipationStatus.JOINED, null);
        setUpMeetingAndActiveTeamMember(meeting, requestMember);
        when(meetingParticipantRepository.findCurrentParticipantsByMeetingId(MEETING_ID))
                .thenReturn(List.of(first, second));

        MeetingParticipantListResponse response = meetingParticipantService.getParticipants(USER_ID, MEETING_ID);

        assertThat(response.participants()).hasSize(2);
        assertThat(response.participants()).extracting("participantId").containsExactly(100L, 101L);
        assertThat(response.participants()).extracting("displayName").containsExactly("김회의", "이기록");
        assertThat(response.participants()).extracting("participationStatus")
                .containsExactly(ParticipationStatus.JOINED, ParticipationStatus.JOINED);
    }

    @Test
    @DisplayName("현재 참석자가 없으면 빈 목록을 반환한다")
    void getParticipantsEmpty() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findCurrentParticipantsByMeetingId(MEETING_ID)).thenReturn(List.of());

        MeetingParticipantListResponse response = meetingParticipantService.getParticipants(USER_ID, MEETING_ID);

        assertThat(response.participants()).isEmpty();
    }

    @Test
    @DisplayName("활성 팀원이 아니면 참석자 목록을 조회할 수 없다")
    void getParticipantsWithoutActiveTeamMember() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingParticipantService.getParticipants(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_ACCESS_DENIED);

        verify(meetingParticipantRepository, never()).findCurrentParticipantsByMeetingId(any());
    }

    @Test
    @DisplayName("WAITING 회의에서 나갈 수 있다")
    void leaveWaitingMeeting() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        MeetingParticipant participant = participant(meeting, teamMember, ParticipationStatus.JOINED, null);
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.of(participant));

        meetingParticipantService.leaveMeeting(USER_ID, MEETING_ID);

        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.LEFT);
        assertThat(participant.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 12, 0));
        ArgumentCaptor<MeetingParticipantLeftEvent> event =
                ArgumentCaptor.forClass(MeetingParticipantLeftEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().meetingId()).isEqualTo(MEETING_ID);
        assertThat(event.getValue().userId()).isEqualTo(USER_ID);
        verify(meetingParticipantRepository, never()).delete(any());
    }

    @Test
    @DisplayName("IN_PROGRESS 회의에서는 나갈 수 없다")
    void leaveInProgressMeetingFails() {
        Meeting meeting = meeting(MeetingStatus.IN_PROGRESS);
        TeamMember teamMember = teamMember("홍길동");
        MeetingParticipant participant = participant(meeting, teamMember, ParticipationStatus.JOINED, null);
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> meetingParticipantService.leaveMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_PARTICIPATION_NOT_ALLOWED);

        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.JOINED);
        assertThat(participant.getDeletedAt()).isNull();
        verify(meetingParticipantRepository, never()).findByMeetingIdAndTeamMemberId(any(), any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    @DisplayName("COMPLETED 회의에서는 나갈 수 없다")
    void leaveCompletedMeetingFails() {
        Meeting meeting = meeting(MeetingStatus.COMPLETED);
        TeamMember teamMember = teamMember("홍길동");
        setUpMeetingAndActiveTeamMember(meeting, teamMember);

        assertThatThrownBy(() -> meetingParticipantService.leaveMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_PARTICIPATION_NOT_ALLOWED);

        verify(meetingParticipantRepository, never()).findByMeetingIdAndTeamMemberId(any(), any());
    }

    @Test
    @DisplayName("참석자 row가 없으면 나가기 404 예외를 던진다")
    void leaveWithoutParticipantFails() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingParticipantService.leaveMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.PARTICIPANT_NOT_FOUND);
    }

    @Test
    @DisplayName("이미 LEFT 상태이면 나가기 409 예외를 던진다")
    void leaveLeftParticipantFails() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        MeetingParticipant participant = participant(
                meeting,
                teamMember,
                ParticipationStatus.LEFT,
                LocalDateTime.of(2026, 9, 15, 11, 0)
        );
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> meetingParticipantService.leaveMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.ALREADY_LEFT);

        assertThat(participant.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 11, 0));
    }

    @Test
    @DisplayName("DISCONNECTED 상태이면 나가기 상태 충돌 예외를 던진다")
    void leaveDisconnectedParticipantFails() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        MeetingParticipant participant = participant(meeting, teamMember, ParticipationStatus.DISCONNECTED, null);
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> meetingParticipantService.leaveMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.PARTICIPANT_STATUS_CONFLICT);
    }

    @Test
    @DisplayName("JOINED 상태이지만 deletedAt이 있으면 나가기 상태 충돌 예외를 던진다")
    void leaveSoftDeletedJoinedParticipantFails() {
        Meeting meeting = meeting(MeetingStatus.WAITING);
        TeamMember teamMember = teamMember("홍길동");
        MeetingParticipant participant = participant(
                meeting,
                teamMember,
                ParticipationStatus.JOINED,
                LocalDateTime.of(2026, 9, 15, 11, 0)
        );
        setUpMeetingAndActiveTeamMember(meeting, teamMember);
        when(meetingParticipantRepository.findByMeetingIdAndTeamMemberId(MEETING_ID, TEAM_MEMBER_ID))
                .thenReturn(Optional.of(participant));

        assertThatThrownBy(() -> meetingParticipantService.leaveMeeting(USER_ID, MEETING_ID))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.PARTICIPANT_STATUS_CONFLICT);

        assertThat(participant.getParticipationStatus()).isEqualTo(ParticipationStatus.JOINED);
        assertThat(participant.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 11, 0));
    }

    private void setUpMeetingAndActiveTeamMember(Meeting meeting, TeamMember teamMember) {
        when(meetingRepository.findByIdAndDeletedAtIsNull(MEETING_ID)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.of(teamMember));
    }

    private Meeting meeting(MeetingStatus status) {
        Team team = org.mockito.Mockito.mock(Team.class);
        TeamMember createdBy = org.mockito.Mockito.mock(TeamMember.class);
        when(team.getId()).thenReturn(TEAM_ID);

        Meeting meeting = Meeting.create(
                team,
                createdBy,
                "회의 제목",
                "회의 목적",
                "회의 메모",
                LocalDateTime.of(2026, 9, 15, 12, 0),
                30
        );
        ReflectionTestUtils.setField(meeting, "id", MEETING_ID);
        ReflectionTestUtils.setField(meeting, "status", status);
        return meeting;
    }

    private TeamMember teamMember(String displayName) {
        return teamMember(TEAM_MEMBER_ID, displayName);
    }

    private TeamMember teamMember(Long teamMemberId, String displayName) {
        TeamMember teamMember = org.mockito.Mockito.mock(TeamMember.class);
        when(teamMember.getId()).thenReturn(teamMemberId);
        when(teamMember.getDisplayName()).thenReturn(displayName);
        return teamMember;
    }

    private MeetingParticipant participant(
            Meeting meeting,
            TeamMember teamMember,
            ParticipationStatus status,
            LocalDateTime deletedAt
    ) {
        return participant(PARTICIPANT_ID, meeting, teamMember, status, deletedAt);
    }

    private MeetingParticipant participant(
            Long participantId,
            Meeting meeting,
            TeamMember teamMember,
            ParticipationStatus status,
            LocalDateTime deletedAt
    ) {
        MeetingParticipant participant = MeetingParticipant.create(meeting, teamMember);
        ReflectionTestUtils.setField(participant, "id", participantId);
        ReflectionTestUtils.setField(participant, "participationStatus", status);
        ReflectionTestUtils.setField(participant, "deletedAt", deletedAt);
        ReflectionTestUtils.setField(participant, "createdAt", LocalDateTime.of(2026, 9, 15, 10, 0));
        return participant;
    }
}
