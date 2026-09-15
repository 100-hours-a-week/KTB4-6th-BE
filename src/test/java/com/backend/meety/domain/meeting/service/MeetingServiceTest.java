package com.backend.meety.domain.meeting.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.meeting.dto.MeetingCreateRequest;
import com.backend.meety.domain.meeting.dto.MeetingCreateResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;

class MeetingServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 2L;
    private static final Long TEAM_MEMBER_ID = 3L;
    private static final ZoneId KST_ZONE_ID = ZoneId.of("Asia/Seoul");

    private MeetingRepository meetingRepository;
    private TeamRepository teamRepository;
    private TeamMemberRepository teamMemberRepository;
    private MeetingService meetingService;

    @BeforeEach
    void setUp() {
        meetingRepository = mock(MeetingRepository.class);
        teamRepository = mock(TeamRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        meetingService = new MeetingService(meetingRepository, teamRepository, teamMemberRepository);
    }

    @Test
    @DisplayName("오늘 생성된 회의가 0개이면 회의를 생성할 수 있다")
    void createMeetingWithZeroMeetingsToday() {
        MeetingCreateResponse response = createMeetingWithTodayCount(0L, createRequest(30, "API 명세 검토"));

        assertThat(response.meetingId()).isEqualTo(100L);
        assertThat(response.teamId()).isEqualTo(TEAM_ID);
        assertThat(response.createdByTeamMemberId()).isEqualTo(TEAM_MEMBER_ID);
        assertThat(response.targetDurationMinutes()).isEqualTo(30);
        assertThat(response.status()).isEqualTo(MeetingStatus.WAITING);
    }

    @Test
    @DisplayName("오늘 생성된 회의가 4개이면 5번째 회의를 생성할 수 있다")
    void createFifthMeeting() {
        MeetingCreateResponse response = createMeetingWithTodayCount(4L, createRequest(30, "API 명세 검토"));

        assertThat(response.meetingId()).isEqualTo(100L);
        assertThat(response.status()).isEqualTo(MeetingStatus.WAITING);
    }

    @Test
    @DisplayName("목표 시간이 1분이어도 회의를 생성할 수 있다")
    void createMeetingWithOneMinuteDuration() {
        MeetingCreateResponse response = createMeetingWithTodayCount(0L, createRequest(1, "API 명세 검토"));

        assertThat(response.targetDurationMinutes()).isEqualTo(1);
    }

    @Test
    @DisplayName("목표 시간이 60분이어도 회의를 생성할 수 있다")
    void createMeetingWithSixtyMinuteDuration() {
        MeetingCreateResponse response = createMeetingWithTodayCount(0L, createRequest(60, "API 명세 검토"));

        assertThat(response.targetDurationMinutes()).isEqualTo(60);
    }

    @Test
    @DisplayName("회의 메모가 null이어도 회의를 생성할 수 있다")
    void createMeetingWithoutNote() {
        MeetingCreateResponse response = createMeetingWithTodayCount(0L, createRequest(30, null));

        assertThat(response.note()).isNull();
    }

    @Test
    @DisplayName("팀 row lock 이후 당일 생성 수를 확인하고 회의를 저장한다")
    void createMeetingWithLockAndCountBeforeSave() {
        Team team = mock(Team.class);
        TeamMember teamMember = mock(TeamMember.class);
        MeetingCreateRequest request = createRequest(30, "API 명세 검토");

        when(team.getId()).thenReturn(TEAM_ID);
        when(teamMember.getId()).thenReturn(TEAM_MEMBER_ID);
        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.of(teamMember));
        when(meetingRepository.countCreatedTodayByTeamId(any(), any(), any())).thenReturn(0L);
        when(meetingRepository.saveAndFlush(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting = invocation.getArgument(0);
            ReflectionTestUtils.setField(meeting, "id", 100L);
            ReflectionTestUtils.setField(meeting, "createdAt", LocalDateTime.of(2026, 9, 15, 9, 0));
            return meeting;
        });

        MeetingCreateResponse response = meetingService.createMeeting(USER_ID, TEAM_ID, request);

        assertThat(response.title()).isEqualTo(request.title());
        assertThat(response.purpose()).isEqualTo(request.purpose());
        assertThat(response.note()).isEqualTo(request.note());
        assertThat(response.scheduledAt()).isEqualTo(request.scheduledAt());

        ArgumentCaptor<Meeting> meetingCaptor = ArgumentCaptor.forClass(Meeting.class);
        InOrder inOrder = inOrder(teamRepository, teamMemberRepository, meetingRepository);
        inOrder.verify(teamRepository).findByIdForUpdate(TEAM_ID);
        LocalDate today = LocalDate.now(KST_ZONE_ID);
        inOrder.verify(meetingRepository).countCreatedTodayByTeamId(
                TEAM_ID,
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()
        );
        inOrder.verify(teamMemberRepository).findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        );
        inOrder.verify(meetingRepository).saveAndFlush(meetingCaptor.capture());

        Meeting savedMeeting = meetingCaptor.getValue();
        assertThat(savedMeeting.getTeam()).isSameAs(team);
        assertThat(savedMeeting.getCreatedByTeamMember()).isSameAs(teamMember);
        assertThat(savedMeeting.getStatus()).isEqualTo(MeetingStatus.WAITING);
    }

    @Test
    @DisplayName("존재하지 않는 팀이면 회의를 생성할 수 없다")
    void createMeetingWithNotFoundTeam() {
        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.createMeeting(USER_ID, TEAM_ID, createRequest(30, "API 명세 검토")))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.TEAM_NOT_FOUND);

        verify(teamMemberRepository, never()).findByTeamIdAndUserIdAndMembershipStatus(any(), any(), any());
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("활성 팀원이 아니면 회의를 생성할 수 없다")
    void createMeetingWithoutActiveMembership() {
        Team team = mock(Team.class);

        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.of(team));
        when(meetingRepository.countCreatedTodayByTeamId(any(), any(), any())).thenReturn(0L);
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.createMeeting(USER_ID, TEAM_ID, createRequest(30, "API 명세 검토")))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.TEAM_MEMBERSHIP_REQUIRED);

        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("비활성 팀원은 회의를 생성할 수 없다")
    void createMeetingWithInactiveMembership() {
        Team team = mock(Team.class);

        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.of(team));
        when(meetingRepository.countCreatedTodayByTeamId(any(), any(), any())).thenReturn(0L);
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.createMeeting(USER_ID, TEAM_ID, createRequest(30, "API 명세 검토")))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.TEAM_MEMBERSHIP_REQUIRED);

        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("오늘 생성된 회의가 5개이면 회의를 생성할 수 없다")
    void createMeetingWithDailyLimitExceeded() {
        setUpCreatableMemberWithTodayCount(5L);

        assertThatThrownBy(() -> meetingService.createMeeting(USER_ID, TEAM_ID, createRequest(30, "API 명세 검토")))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.DAILY_MEETING_LIMIT_EXCEEDED);

        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("COMPLETED 회의를 포함해 오늘 생성된 회의가 5개이면 회의를 생성할 수 없다")
    void createMeetingWithCompletedMeetingsIncludedInDailyLimit() {
        setUpCreatableMemberWithTodayCount(5L);

        assertThatThrownBy(() -> meetingService.createMeeting(USER_ID, TEAM_ID, createRequest(30, "API 명세 검토")))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.DAILY_MEETING_LIMIT_EXCEEDED);

        verify(meetingRepository).countCreatedTodayByTeamId(any(), any(), any());
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("soft deleted 회의를 포함해 오늘 생성된 회의가 5개이면 회의를 생성할 수 없다")
    void createMeetingWithSoftDeletedMeetingsIncludedInDailyLimit() {
        setUpCreatableMemberWithTodayCount(5L);

        assertThatThrownBy(() -> meetingService.createMeeting(USER_ID, TEAM_ID, createRequest(30, "API 명세 검토")))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.DAILY_MEETING_LIMIT_EXCEEDED);

        verify(meetingRepository).countCreatedTodayByTeamId(any(), any(), any());
        verify(meetingRepository, never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("어제 회의가 5개여도 오늘 생성된 회의가 0개이면 회의를 생성할 수 있다")
    void createMeetingWithFiveMeetingsYesterdayAndZeroToday() {
        MeetingCreateResponse response = createMeetingWithTodayCount(0L, createRequest(30, "API 명세 검토"));

        assertThat(response.meetingId()).isEqualTo(100L);
        LocalDate today = LocalDate.now(KST_ZONE_ID);
        verify(meetingRepository).countCreatedTodayByTeamId(
                TEAM_ID,
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()
        );
    }

    @Test
    @DisplayName("회의 저장 중 DB 예외가 발생하면 생성 실패 예외를 던진다")
    void createMeetingFailed() {
        Team team = mock(Team.class);
        TeamMember teamMember = mock(TeamMember.class);

        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.of(teamMember));
        when(meetingRepository.countCreatedTodayByTeamId(any(), any(), any())).thenReturn(0L);
        when(meetingRepository.saveAndFlush(any(Meeting.class)))
                .thenThrow(new DataIntegrityViolationException("save failed"));

        assertThatThrownBy(() -> meetingService.createMeeting(USER_ID, TEAM_ID, createRequest(30, "API 명세 검토")))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_CREATE_FAILED);
    }

    private MeetingCreateResponse createMeetingWithTodayCount(long todayCount, MeetingCreateRequest request) {
        setUpCreatableMemberWithTodayCount(todayCount);
        when(meetingRepository.saveAndFlush(any(Meeting.class))).thenAnswer(invocation -> {
            Meeting meeting = invocation.getArgument(0);
            ReflectionTestUtils.setField(meeting, "id", 100L);
            ReflectionTestUtils.setField(meeting, "createdAt", LocalDateTime.of(2026, 9, 15, 9, 0));
            return meeting;
        });

        return meetingService.createMeeting(USER_ID, TEAM_ID, request);
    }

    private void setUpCreatableMemberWithTodayCount(long todayCount) {
        Team team = mock(Team.class);
        TeamMember teamMember = mock(TeamMember.class);

        when(team.getId()).thenReturn(TEAM_ID);
        when(teamMember.getId()).thenReturn(TEAM_MEMBER_ID);
        when(teamRepository.findByIdForUpdate(TEAM_ID)).thenReturn(Optional.of(team));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.of(teamMember));
        when(meetingRepository.countCreatedTodayByTeamId(any(), any(), any())).thenReturn(todayCount);
    }

    private MeetingCreateRequest createRequest(Integer targetDurationMinutes, String note) {
        return new MeetingCreateRequest(
                "주간 스프린트 회의",
                "진행 상황과 이슈 공유",
                note,
                LocalDateTime.of(2026, 9, 15, 15, 0),
                targetDurationMinutes
        );
    }
}
