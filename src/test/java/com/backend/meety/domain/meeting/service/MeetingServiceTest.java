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
import com.backend.meety.domain.meeting.dto.MeetingCalendarResponse;
import com.backend.meety.domain.meeting.dto.MeetingDetailResponse;
import com.backend.meety.domain.meeting.dto.MeetingListResponse;
import com.backend.meety.domain.meeting.dto.MeetingUpdateRequest;
import com.backend.meety.domain.meeting.dto.MeetingUpdateResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.repository.support.MeetingCursor;
import com.backend.meety.domain.meeting.repository.support.MeetingListSearchCondition;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.entity.TeamMemberRole;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-15T03:00:00Z"),
            KST_ZONE_ID
    );

    private MeetingRepository meetingRepository;
    private TeamRepository teamRepository;
    private TeamMemberRepository teamMemberRepository;
    private MeetingService meetingService;

    @BeforeEach
    void setUp() {
        meetingRepository = mock(MeetingRepository.class);
        teamRepository = mock(TeamRepository.class);
        teamMemberRepository = mock(TeamMemberRepository.class);
        meetingService = new MeetingService(meetingRepository, teamRepository, teamMemberRepository, FIXED_CLOCK);
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
        assertThat(response.scheduledAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));

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
        assertThat(savedMeeting.getScheduledAt()).isEqualTo(LocalDateTime.now(FIXED_CLOCK));
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

    @Test
    @DisplayName("회의 상세를 조회한다")
    void getMeeting() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 15, 15, 0), null);

        when(meetingRepository.findDetailByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(true);

        MeetingDetailResponse response = meetingService.getMeeting(USER_ID, 100L);

        assertThat(response.meetingId()).isEqualTo(100L);
        assertThat(response.teamId()).isEqualTo(TEAM_ID);
        assertThat(response.createdByTeamMemberId()).isEqualTo(TEAM_MEMBER_ID);
        assertThat(response.title()).isEqualTo("회의 100");
        assertThat(response.status()).isEqualTo(MeetingStatus.WAITING);
        assertThat(response.createdAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 9, 0));
        assertThat(response.updatedAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 9, 30));
    }

    @Test
    @DisplayName("회의가 없거나 삭제된 회의이면 상세 조회에 실패한다")
    void getMeetingNotFound() {
        when(meetingRepository.findDetailByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.getMeeting(USER_ID, 100L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_NOT_FOUND);

        verify(teamMemberRepository, never()).existsByTeamIdAndUserIdAndMembershipStatus(any(), any(), any());
    }

    @Test
    @DisplayName("활성 팀원이 아니면 회의 상세 조회에 실패한다")
    void getMeetingAccessDenied() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 15, 15, 0), null);

        when(meetingRepository.findDetailByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(false);

        assertThatThrownBy(() -> meetingService.getMeeting(USER_ID, 100L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_ACCESS_DENIED);
    }

    @Test
    @DisplayName("활성 팀원은 회의 목록을 조회할 수 있다")
    void getMeetings() {
        setUpListPermission();
        List<LocalDate> dates = List.of(LocalDate.of(2026, 9, 16), LocalDate.of(2026, 9, 15));
        List<Meeting> meetings = List.of(
                meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 16, 9, 0), null),
                meeting(102L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 16, 14, 0), null),
                meeting(103L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 9, 15, 16, 0),
                        LocalDateTime.of(2026, 9, 15, 10, 0))
        );
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(dates);
        when(meetingRepository.findMeetingsByDates(any(), any())).thenReturn(meetings);

        MeetingListResponse response = meetingService.getMeetings(
                USER_ID,
                TEAM_ID,
                "  스프린트  ",
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 16),
                null
        );

        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.groups()).hasSize(2);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(response.groups().get(0).meetingCount()).isEqualTo(2);
        assertThat(response.groups().get(1).date()).isEqualTo(LocalDate.of(2026, 9, 15));

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository).findMeetingDatesByCondition(conditionCaptor.capture());
        MeetingListSearchCondition condition = conditionCaptor.getValue();
        assertThat(condition.keyword()).isEqualTo("스프린트");
        assertThat(condition.fromInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(condition.toExclusive()).isEqualTo(LocalDateTime.of(2026, 9, 17, 0, 0));
        assertThat(condition.limit()).isEqualTo(6);
        verify(meetingRepository).findMeetingsByDates(condition, dates);
    }

    @Test
    @DisplayName("목록 조회 keyword가 null 또는 blank이면 검색 조건을 사용하지 않는다")
    void getMeetingsWithoutKeyword() {
        setUpListPermission();
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(List.of());

        meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null);
        meetingService.getMeetings(USER_ID, TEAM_ID, "   ", null, null, null);

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository, org.mockito.Mockito.times(2)).findMeetingDatesByCondition(conditionCaptor.capture());
        assertThat(conditionCaptor.getAllValues()).extracting(MeetingListSearchCondition::keyword)
                .containsExactly(null, null);
        assertThat(conditionCaptor.getAllValues()).extracting(MeetingListSearchCondition::limit)
                .containsExactly(6, 6);
    }

    @Test
    @DisplayName("from만 있으면 시작일 00:00 이상 조건을 전달한다")
    void getMeetingsWithFromOnly() {
        setUpListPermission();
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(List.of());

        meetingService.getMeetings(USER_ID, TEAM_ID, null, LocalDate.of(2026, 9, 15), null, null);

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository).findMeetingDatesByCondition(conditionCaptor.capture());
        assertThat(conditionCaptor.getValue().fromInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(conditionCaptor.getValue().toExclusive()).isNull();
    }

    @Test
    @DisplayName("to만 있으면 종료일 다음날 00:00 미만 조건을 전달한다")
    void getMeetingsWithToOnly() {
        setUpListPermission();
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(List.of());

        meetingService.getMeetings(USER_ID, TEAM_ID, null, null, LocalDate.of(2026, 9, 15), null);

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository).findMeetingDatesByCondition(conditionCaptor.capture());
        assertThat(conditionCaptor.getValue().fromInclusive()).isNull();
        assertThat(conditionCaptor.getValue().toExclusive()).isEqualTo(LocalDateTime.of(2026, 9, 16, 0, 0));
    }

    @Test
    @DisplayName("WAITING은 scheduledAt 기준으로 날짜 그룹을 만든다")
    void groupWaitingMeetingByScheduledAt() {
        setUpListPermission();
        Meeting meeting = meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 16, 9, 0), null);
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(List.of(LocalDate.of(2026, 9, 16)));
        when(meetingRepository.findMeetingsByDates(any(), any())).thenReturn(List.of(meeting));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null);

        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 16));
    }

    @Test
    @DisplayName("IN_PROGRESS는 startedAt 기준으로 날짜 그룹을 만든다")
    void groupInProgressMeetingByStartedAt() {
        setUpListPermission();
        Meeting meeting = meeting(101L, MeetingStatus.IN_PROGRESS, LocalDateTime.of(2026, 9, 16, 9, 0),
                LocalDateTime.of(2026, 9, 15, 10, 0));
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(List.of(LocalDate.of(2026, 9, 15)));
        when(meetingRepository.findMeetingsByDates(any(), any())).thenReturn(List.of(meeting));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null);

        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("COMPLETED는 startedAt 기준으로 날짜 그룹을 만든다")
    void groupCompletedMeetingByStartedAt() {
        setUpListPermission();
        Meeting meeting = meeting(101L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 9, 16, 9, 0),
                LocalDateTime.of(2026, 9, 15, 10, 0));
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(List.of(LocalDate.of(2026, 9, 15)));
        when(meetingRepository.findMeetingsByDates(any(), any())).thenReturn(List.of(meeting));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null);

        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("날짜 그룹이 6개이면 5개만 반환하고 마지막 날짜 cursor를 반환한다")
    void getMeetingsWithNextDateCursor() {
        setUpListPermission();
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 13),
                LocalDate.of(2026, 9, 12)
        );
        List<Meeting> meetings = List.of(
                meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 17, 9, 0), null),
                meeting(102L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 16, 9, 0), null),
                meeting(103L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 15, 9, 0), null),
                meeting(104L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 14, 9, 0), null),
                meeting(105L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 13, 9, 0), null)
        );
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(dates);
        when(meetingRepository.findMeetingsByDates(any(), any())).thenReturn(meetings);

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null);

        assertThat(response.hasNext()).isTrue();
        assertThat(response.groups()).hasSize(5);
        assertThat(MeetingCursor.decode(response.nextCursor()))
                .isEqualTo(new MeetingCursor(LocalDate.of(2026, 9, 13)));

        ArgumentCaptor<List<LocalDate>> datesCaptor = ArgumentCaptor.forClass(List.class);
        verify(meetingRepository).findMeetingsByDates(any(), datesCaptor.capture());
        assertThat(datesCaptor.getValue()).containsExactly(
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 13)
        );
    }

    @Test
    @DisplayName("한 날짜에 회의가 여러 개이면 날짜 그룹이 페이지 사이에서 분할되지 않는다")
    void getMeetingsDoesNotSplitDateGroup() {
        setUpListPermission();
        Meeting first = meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 15, 9, 0), null);
        Meeting second = meeting(102L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 15, 10, 0), null);
        Meeting third = meeting(103L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 15, 11, 0), null);
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(List.of(LocalDate.of(2026, 9, 15)));
        when(meetingRepository.findMeetingsByDates(any(), any())).thenReturn(List.of(first, second, third));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null);

        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(response.groups().get(0).meetingCount()).isEqualTo(3);
        assertThat(response.groups().get(0).meetings()).extracting("meetingId").containsExactly(101L, 102L, 103L);
    }

    @Test
    @DisplayName("cursor가 있으면 마지막 응답 날짜보다 이전 날짜 그룹을 조회한다")
    void getMeetingsWithCursor() {
        setUpListPermission();
        MeetingCursor cursor = new MeetingCursor(LocalDate.of(2026, 9, 13));
        Meeting meeting = meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 12, 9, 0), null);
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(List.of(LocalDate.of(2026, 9, 12)));
        when(meetingRepository.findMeetingsByDates(any(), any())).thenReturn(List.of(meeting));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, cursor.encode());

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository).findMeetingDatesByCondition(conditionCaptor.capture());
        assertThat(conditionCaptor.getValue().cursorDate()).isEqualTo(LocalDate.of(2026, 9, 13));
        assertThat(response.groups()).extracting("date").containsExactly(LocalDate.of(2026, 9, 12));
    }

    @Test
    @DisplayName("조건에 맞는 날짜 그룹이 없으면 빈 결과를 반환하고 회의 조회를 하지 않는다")
    void getMeetingsEmpty() {
        setUpListPermission();
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(List.of());

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null);

        assertThat(response.groups()).isEmpty();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.hasNext()).isFalse();
        verify(meetingRepository, never()).findMeetingsByDates(any(), any());
    }

    @Test
    @DisplayName("5개 날짜 각각 회의 5개이면 25개 회의를 모두 반환한다")
    void getMeetingsReturnsAllMeetingsInFiveDateGroups() {
        setUpListPermission();
        List<LocalDate> dates = List.of(
                LocalDate.of(2026, 9, 17),
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 14),
                LocalDate.of(2026, 9, 13)
        );
        List<Meeting> meetings = new ArrayList<>();
        long meetingId = 100L;
        for (LocalDate date : dates) {
            for (int hour = 9; hour < 14; hour++) {
                meetings.add(meeting(meetingId++, MeetingStatus.WAITING, date.atTime(hour, 0), null));
            }
        }
        when(meetingRepository.findMeetingDatesByCondition(any())).thenReturn(dates);
        when(meetingRepository.findMeetingsByDates(any(), any())).thenReturn(meetings);

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null);

        assertThat(response.groups()).hasSize(5);
        assertThat(response.groups()).extracting("meetingCount").containsExactly(5, 5, 5, 5, 5);
        assertThat(response.groups().stream().mapToInt(group -> group.meetings().size()).sum()).isEqualTo(25);
    }

    @Test
    @DisplayName("존재하지 않는 팀이면 목록 조회에 실패한다")
    void getMeetingsWithNotFoundTeam() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(false);

        assertThatThrownBy(() -> meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.TEAM_NOT_FOUND);

        verify(meetingRepository, never()).findMeetingDatesByCondition(any());
    }

    @Test
    @DisplayName("활성 팀원이 아니면 목록 조회에 실패한다")
    void getMeetingsWithoutActiveMembership() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(false);

        assertThatThrownBy(() -> meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.TEAM_MEMBERSHIP_REQUIRED);

        verify(meetingRepository, never()).findMeetingDatesByCondition(any());
    }

    @Test
    @DisplayName("from이 to보다 이후이면 목록 조회에 실패한다")
    void getMeetingsWithInvalidDateRange() {
        setUpListPermission();

        assertThatThrownBy(() -> meetingService.getMeetings(
                USER_ID,
                TEAM_ID,
                null,
                LocalDate.of(2026, 9, 16),
                LocalDate.of(2026, 9, 15),
                null
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.INVALID_DATE_RANGE);
    }

    @Test
    @DisplayName("잘못된 cursor이면 목록 조회에 실패한다")
    void getMeetingsWithInvalidCursor() {
        setUpListPermission();

        assertThatThrownBy(() -> meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, "invalid"))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.INVALID_CURSOR);
    }

    @Test
    @DisplayName("ACTIVE 팀원은 캘린더를 조회할 수 있고 월 경계를 전달한다")
    void getMeetingCalendar() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(true);
        Meeting first = meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 3, 9, 0), null);
        Meeting second = meeting(102L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 18, 10, 0), null);
        when(meetingRepository.findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(
                TEAM_ID,
                LocalDateTime.of(2026, 9, 1, 0, 0),
                LocalDateTime.of(2026, 10, 1, 0, 0)
        )).thenReturn(List.of(first, second));

        MeetingCalendarResponse response = meetingService.getMeetingCalendar(USER_ID, TEAM_ID, 2026, 9);

        assertThat(response.year()).isEqualTo(2026);
        assertThat(response.month()).isEqualTo(9);
        assertThat(response.dates()).hasSize(2);
        assertThat(response.dates()).extracting("date")
                .containsExactly(LocalDate.of(2026, 9, 3), LocalDate.of(2026, 9, 18));
        assertThat(response.dates().get(0).meetings()).extracting("meetingId").containsExactly(101L);
        assertThat(response.dates().get(1).meetings()).extracting("meetingId").containsExactly(102L);
    }

    @Test
    @DisplayName("캘린더는 month 1과 12의 다음 달 경계를 계산한다")
    void getMeetingCalendarMonthBoundaries() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(true);
        when(meetingRepository.findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        meetingService.getMeetingCalendar(USER_ID, TEAM_ID, 2026, 1);
        meetingService.getMeetingCalendar(USER_ID, TEAM_ID, 2026, 12);

        verify(meetingRepository).findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(
                TEAM_ID,
                LocalDateTime.of(2026, 1, 1, 0, 0),
                LocalDateTime.of(2026, 2, 1, 0, 0)
        );
        verify(meetingRepository).findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(
                TEAM_ID,
                LocalDateTime.of(2026, 12, 1, 0, 0),
                LocalDateTime.of(2027, 1, 1, 0, 0)
        );
    }

    @Test
    @DisplayName("캘린더는 WAITING은 scheduledAt, IN_PROGRESS와 COMPLETED는 startedAt 기준으로 그룹화한다")
    void getMeetingCalendarGroupsByEffectiveStartAt() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(true);
        Meeting waiting = meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 3, 9, 0), null);
        Meeting inProgress = meeting(102L, MeetingStatus.IN_PROGRESS, LocalDateTime.of(2026, 9, 4, 9, 0),
                LocalDateTime.of(2026, 9, 3, 10, 0));
        Meeting completed = meeting(103L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 8, 31, 9, 0),
                LocalDateTime.of(2026, 9, 18, 10, 0));
        when(meetingRepository.findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(any(), any(), any()))
                .thenReturn(List.of(waiting, inProgress, completed));

        MeetingCalendarResponse response = meetingService.getMeetingCalendar(USER_ID, TEAM_ID, 2026, 9);

        assertThat(response.dates()).hasSize(2);
        assertThat(response.dates().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 3));
        assertThat(response.dates().get(0).meetings()).extracting("meetingId").containsExactly(101L, 102L);
        assertThat(response.dates().get(1).date()).isEqualTo(LocalDate.of(2026, 9, 18));
        assertThat(response.dates().get(1).meetings()).extracting("meetingId").containsExactly(103L);
    }

    @Test
    @DisplayName("캘린더는 빈 결과이면 dates 빈 배열을 반환한다")
    void getMeetingCalendarEmpty() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(true);
        when(meetingRepository.findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        MeetingCalendarResponse response = meetingService.getMeetingCalendar(USER_ID, TEAM_ID, 2026, 9);

        assertThat(response.year()).isEqualTo(2026);
        assertThat(response.month()).isEqualTo(9);
        assertThat(response.dates()).isEmpty();
    }

    @Test
    @DisplayName("캘린더 조회는 팀이 없으면 실패한다")
    void getMeetingCalendarTeamNotFound() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(false);

        assertThatThrownBy(() -> meetingService.getMeetingCalendar(USER_ID, TEAM_ID, 2026, 9))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.TEAM_NOT_FOUND);

        verify(meetingRepository, never()).findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(any(), any(), any());
    }

    @Test
    @DisplayName("캘린더 조회는 ACTIVE 팀원이 아니면 실패한다")
    void getMeetingCalendarWithoutActiveMembership() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(false);

        assertThatThrownBy(() -> meetingService.getMeetingCalendar(USER_ID, TEAM_ID, 2026, 9))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.TEAM_MEMBERSHIP_REQUIRED);

        verify(meetingRepository, never()).findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(any(), any(), any());
    }

    @Test
    @DisplayName("캘린더 조회는 LocalDate로 생성할 수 없는 year이면 공통 입력 예외를 던진다")
    void getMeetingCalendarWithInvalidYear() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(true);

        assertThatThrownBy(() -> meetingService.getMeetingCalendar(USER_ID, TEAM_ID, Integer.MAX_VALUE, 9))
                .isInstanceOf(com.backend.meety.global.exception.BusinessException.class)
                .extracting("errorCode")
                .isEqualTo(com.backend.meety.global.exception.CommonErrorCode.INVALID_INPUT_VALUE);
    }

    @Test
    @DisplayName("LEADER는 WAITING 회의 정보를 수정할 수 있다")
    void updateWaitingMeetingByLeader() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        TeamMember leader = teamMember(99L, TeamMemberRole.LEADER);
        setUpLockedMeetingAndActiveMember(meeting, leader);

        MeetingUpdateResponse response = meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest(
                        "수정된 회의",
                        "수정된 목적",
                        "수정된 메모",
                        LocalDateTime.of(2026, 9, 21, 10, 0),
                        45
                )
        );

        assertThat(response.title()).isEqualTo("수정된 회의");
        assertThat(response.purpose()).isEqualTo("수정된 목적");
        assertThat(response.note()).isEqualTo("수정된 메모");
        assertThat(response.scheduledAt()).isEqualTo(LocalDateTime.of(2026, 9, 21, 10, 0));
        assertThat(response.targetDurationMinutes()).isEqualTo(45);
        assertThat(meeting.getTitle()).isEqualTo("수정된 회의");
        assertThat(meeting.getPurpose()).isEqualTo("수정된 목적");
        assertThat(meeting.getNote()).isEqualTo("수정된 메모");
        assertThat(meeting.getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 9, 21, 10, 0));
        assertThat(meeting.getTargetDurationMinutes()).isEqualTo(45);
    }

    @Test
    @DisplayName("회의 생성자는 WAITING 회의 정보를 수정할 수 있다")
    void updateWaitingMeetingByCreator() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        TeamMember creator = teamMember(TEAM_MEMBER_ID, TeamMemberRole.MEMBER);
        setUpLockedMeetingAndActiveMember(meeting, creator);

        meetingService.updateMeeting(USER_ID, 100L, new MeetingUpdateRequest("생성자 수정", null, null, null, null));

        assertThat(meeting.getTitle()).isEqualTo("생성자 수정");
        assertThat(meeting.getPurpose()).isEqualTo("진행 상황 공유");
        assertThat(meeting.getNote()).isEqualTo("API 검토");
    }

    @Test
    @DisplayName("일반 ACTIVE 팀원은 회의를 수정할 수 없다")
    void updateMeetingByMemberForbidden() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        TeamMember member = teamMember(99L, TeamMemberRole.MEMBER);
        setUpLockedMeetingAndActiveMember(meeting, member);

        assertThatThrownBy(() -> meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest("권한 없음", null, null, null, null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_UPDATE_FORBIDDEN);

        assertThat(meeting.getTitle()).isEqualTo("회의 100");
    }

    @Test
    @DisplayName("ACTIVE 팀원이 아니면 회의 생성자여도 수정할 수 없다")
    void updateMeetingByInactiveCreatorForbidden() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        when(meetingRepository.findByIdForUpdateAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest("비활성", null, null, null, null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_UPDATE_FORBIDDEN);

        assertThat(meeting.getTitle()).isEqualTo("회의 100");
    }

    @Test
    @DisplayName("WAITING 회의 수정에서 요청하지 않은 필드는 유지된다")
    void updateWaitingMeetingKeepsUnspecifiedFields() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        setUpLockedMeetingAndActiveMember(meeting, teamMember(TEAM_MEMBER_ID, TeamMemberRole.MEMBER));

        meetingService.updateMeeting(USER_ID, 100L, new MeetingUpdateRequest(null, "목적만 수정", null, null, null));

        assertThat(meeting.getTitle()).isEqualTo("회의 100");
        assertThat(meeting.getPurpose()).isEqualTo("목적만 수정");
        assertThat(meeting.getNote()).isEqualTo("API 검토");
        assertThat(meeting.getScheduledAt()).isEqualTo(LocalDateTime.of(2026, 9, 20, 10, 0));
        assertThat(meeting.getTargetDurationMinutes()).isEqualTo(30);
    }

    @Test
    @DisplayName("WAITING 회의 수정에서 note 빈 문자열은 null로 삭제한다")
    void updateWaitingMeetingClearsNote() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        setUpLockedMeetingAndActiveMember(meeting, teamMember(TEAM_MEMBER_ID, TeamMemberRole.MEMBER));

        meetingService.updateMeeting(USER_ID, 100L, new MeetingUpdateRequest(null, null, "", null, null));

        assertThat(meeting.getNote()).isNull();
    }

    @Test
    @DisplayName("빈 PATCH 요청은 no-op으로 처리하고 기존 값을 반환한다")
    void updateMeetingWithEmptyRequestNoop() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        setUpLockedMeetingAndActiveMember(meeting, teamMember(TEAM_MEMBER_ID, TeamMemberRole.MEMBER));

        MeetingUpdateResponse response = meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest(null, null, null, null, null)
        );

        assertThat(response.title()).isEqualTo("회의 100");
        assertThat(response.purpose()).isEqualTo("진행 상황 공유");
        assertThat(response.note()).isEqualTo("API 검토");
    }

    @Test
    @DisplayName("COMPLETED 회의는 title만 수정할 수 있다")
    void updateCompletedMeetingTitleOnly() {
        Meeting meeting = meeting(100L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 9, 20, 10, 0),
                LocalDateTime.of(2026, 9, 20, 10, 0));
        setUpLockedMeetingAndActiveMember(meeting, teamMember(TEAM_MEMBER_ID, TeamMemberRole.MEMBER));

        meetingService.updateMeeting(USER_ID, 100L, new MeetingUpdateRequest("종료 후 이름", null, null, null, null));

        assertThat(meeting.getTitle()).isEqualTo("종료 후 이름");
        assertThat(meeting.getPurpose()).isEqualTo("진행 상황 공유");
    }

    @Test
    @DisplayName("COMPLETED 회의에서 title과 금지 필드를 함께 요청하면 전체 실패하고 부분 수정하지 않는다")
    void updateCompletedMeetingForbiddenFieldFailsWithoutPartialMutation() {
        Meeting meeting = meeting(100L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 9, 20, 10, 0),
                LocalDateTime.of(2026, 9, 20, 10, 0));
        setUpLockedMeetingAndActiveMember(meeting, teamMember(TEAM_MEMBER_ID, TeamMemberRole.MEMBER));

        assertThatThrownBy(() -> meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest("부분 수정 금지", "금지 필드", null, null, null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_FIELD_UPDATE_NOT_ALLOWED);

        assertThat(meeting.getTitle()).isEqualTo("회의 100");
        assertThat(meeting.getPurpose()).isEqualTo("진행 상황 공유");
    }

    @Test
    @DisplayName("COMPLETED 회의에서 note, scheduledAt, duration 수정 요청은 실패한다")
    void updateCompletedMeetingForbiddenFieldsFail() {
        Meeting meeting = meeting(100L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 9, 20, 10, 0),
                LocalDateTime.of(2026, 9, 20, 10, 0));
        setUpLockedMeetingAndActiveMember(meeting, teamMember(TEAM_MEMBER_ID, TeamMemberRole.MEMBER));

        assertThatThrownBy(() -> meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest(null, null, "금지", null, null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_FIELD_UPDATE_NOT_ALLOWED);
        assertThatThrownBy(() -> meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest(null, null, null, LocalDateTime.of(2026, 9, 21, 10, 0), null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_FIELD_UPDATE_NOT_ALLOWED);
        assertThatThrownBy(() -> meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest(null, null, null, null, 40)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_FIELD_UPDATE_NOT_ALLOWED);
    }

    @Test
    @DisplayName("IN_PROGRESS 회의는 수정할 수 없고 Entity가 변경되지 않는다")
    void updateInProgressMeetingFails() {
        Meeting meeting = meeting(100L, MeetingStatus.IN_PROGRESS, LocalDateTime.of(2026, 9, 20, 10, 0),
                LocalDateTime.of(2026, 9, 20, 10, 0));
        setUpLockedMeetingAndActiveMember(meeting, teamMember(TEAM_MEMBER_ID, TeamMemberRole.MEMBER));

        assertThatThrownBy(() -> meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest("진행 중 수정", null, null, null, null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_FIELD_UPDATE_NOT_ALLOWED);

        assertThat(meeting.getTitle()).isEqualTo("회의 100");
    }

    @Test
    @DisplayName("회의가 없거나 삭제된 회의이면 수정할 수 없다")
    void updateMeetingNotFound() {
        when(meetingRepository.findByIdForUpdateAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.updateMeeting(
                USER_ID,
                100L,
                new MeetingUpdateRequest("없음", null, null, null, null)
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_NOT_FOUND);
    }

    @Test
    @DisplayName("LEADER는 WAITING 회의를 soft delete할 수 있다")
    void deleteWaitingMeetingByLeader() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        setUpLockedMeetingAndActiveMember(meeting, teamMember(99L, TeamMemberRole.LEADER));

        meetingService.deleteMeeting(USER_ID, 100L);

        assertThat(meeting.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 12, 0));
        verify(meetingRepository, never()).delete(any());
    }

    @Test
    @DisplayName("LEADER는 COMPLETED 회의를 soft delete할 수 있다")
    void deleteCompletedMeetingByLeader() {
        Meeting meeting = meeting(100L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 9, 20, 10, 0),
                LocalDateTime.of(2026, 9, 20, 10, 0));
        setUpLockedMeetingAndActiveMember(meeting, teamMember(99L, TeamMemberRole.LEADER));

        meetingService.deleteMeeting(USER_ID, 100L);

        assertThat(meeting.getDeletedAt()).isEqualTo(LocalDateTime.of(2026, 9, 15, 12, 0));
    }

    @Test
    @DisplayName("회의 생성자라도 LEADER가 아니면 삭제할 수 없다")
    void deleteMeetingByCreatorMemberForbidden() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        setUpLockedMeetingAndActiveMember(meeting, teamMember(TEAM_MEMBER_ID, TeamMemberRole.MEMBER));

        assertThatThrownBy(() -> meetingService.deleteMeeting(USER_ID, 100L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_DELETE_FORBIDDEN);

        assertThat(meeting.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("일반 ACTIVE 팀원은 삭제할 수 없다")
    void deleteMeetingByMemberForbidden() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        setUpLockedMeetingAndActiveMember(meeting, teamMember(99L, TeamMemberRole.MEMBER));

        assertThatThrownBy(() -> meetingService.deleteMeeting(USER_ID, 100L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_DELETE_FORBIDDEN);
    }

    @Test
    @DisplayName("ACTIVE 팀원이 아니면 삭제할 수 없다")
    void deleteMeetingByInactiveMemberForbidden() {
        Meeting meeting = meeting(100L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 20, 10, 0), null);
        when(meetingRepository.findByIdForUpdateAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.deleteMeeting(USER_ID, 100L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_DELETE_FORBIDDEN);
    }

    @Test
    @DisplayName("IN_PROGRESS 회의는 삭제할 수 없다")
    void deleteInProgressMeetingFails() {
        Meeting meeting = meeting(100L, MeetingStatus.IN_PROGRESS, LocalDateTime.of(2026, 9, 20, 10, 0),
                LocalDateTime.of(2026, 9, 20, 10, 0));
        setUpLockedMeetingAndActiveMember(meeting, teamMember(99L, TeamMemberRole.LEADER));

        assertThatThrownBy(() -> meetingService.deleteMeeting(USER_ID, 100L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_IN_PROGRESS);

        assertThat(meeting.getDeletedAt()).isNull();
    }

    @Test
    @DisplayName("회의가 없거나 이미 삭제된 회의이면 삭제할 수 없다")
    void deleteMeetingNotFound() {
        when(meetingRepository.findByIdForUpdateAndDeletedAtIsNull(100L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> meetingService.deleteMeeting(USER_ID, 100L))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.MEETING_NOT_FOUND);
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
                targetDurationMinutes
        );
    }

    private void setUpListPermission() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(true);
    }

    private void setUpLockedMeetingAndActiveMember(Meeting meeting, TeamMember teamMember) {
        when(meetingRepository.findByIdForUpdateAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(Optional.of(teamMember));
    }

    private TeamMember teamMember(Long teamMemberId, TeamMemberRole role) {
        TeamMember teamMember = mock(TeamMember.class);
        when(teamMember.getId()).thenReturn(teamMemberId);
        when(teamMember.getRole()).thenReturn(role);
        return teamMember;
    }

    private Meeting meeting(
            Long meetingId,
            MeetingStatus status,
            LocalDateTime scheduledAt,
            LocalDateTime startedAt
    ) {
        Team team = mock(Team.class);
        TeamMember teamMember = mock(TeamMember.class);
        when(team.getId()).thenReturn(TEAM_ID);
        when(teamMember.getId()).thenReturn(TEAM_MEMBER_ID);

        Meeting meeting = Meeting.create(
                team,
                teamMember,
                "회의 " + meetingId,
                "진행 상황 공유",
                "API 검토",
                scheduledAt,
                30
        );
        ReflectionTestUtils.setField(meeting, "id", meetingId);
        ReflectionTestUtils.setField(meeting, "status", status);
        ReflectionTestUtils.setField(meeting, "startedAt", startedAt);
        ReflectionTestUtils.setField(meeting, "endedAt", null);
        ReflectionTestUtils.setField(meeting, "createdAt", LocalDateTime.of(2026, 9, 15, 9, 0));
        ReflectionTestUtils.setField(meeting, "updatedAt", LocalDateTime.of(2026, 9, 15, 9, 30));
        return meeting;
    }
}
