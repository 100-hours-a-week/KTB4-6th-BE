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
import com.backend.meety.domain.meeting.dto.MeetingDetailResponse;
import com.backend.meety.domain.meeting.dto.MeetingListResponse;
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
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.team.repository.TeamRepository;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
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
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(true);
        List<Meeting> meetings = List.of(
                meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 16, 9, 0), null),
                meeting(102L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 16, 14, 0), null),
                meeting(103L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 9, 15, 16, 0),
                        LocalDateTime.of(2026, 9, 15, 10, 0))
        );
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(meetings);

        MeetingListResponse response = meetingService.getMeetings(
                USER_ID,
                TEAM_ID,
                "  스프린트  ",
                LocalDate.of(2026, 9, 15),
                LocalDate.of(2026, 9, 16),
                null,
                20
        );

        assertThat(response.hasNext()).isFalse();
        assertThat(response.nextCursor()).isNull();
        assertThat(response.groups()).hasSize(2);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 16));
        assertThat(response.groups().get(0).meetingCount()).isEqualTo(2);
        assertThat(response.groups().get(1).date()).isEqualTo(LocalDate.of(2026, 9, 15));

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository).findMeetingsByCondition(conditionCaptor.capture());
        MeetingListSearchCondition condition = conditionCaptor.getValue();
        assertThat(condition.keyword()).isEqualTo("스프린트");
        assertThat(condition.fromInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(condition.toExclusive()).isEqualTo(LocalDateTime.of(2026, 9, 17, 0, 0));
        assertThat(condition.limit()).isEqualTo(21);
    }

    @Test
    @DisplayName("목록 조회 keyword가 null 또는 blank이면 검색 조건을 사용하지 않는다")
    void getMeetingsWithoutKeyword() {
        setUpListPermission();
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(List.of());

        meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, null);
        meetingService.getMeetings(USER_ID, TEAM_ID, "   ", null, null, null, null);

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository, org.mockito.Mockito.times(2)).findMeetingsByCondition(conditionCaptor.capture());
        assertThat(conditionCaptor.getAllValues()).extracting(MeetingListSearchCondition::keyword)
                .containsExactly(null, null);
        assertThat(conditionCaptor.getAllValues()).extracting(MeetingListSearchCondition::limit)
                .containsExactly(21, 21);
    }

    @Test
    @DisplayName("from만 있으면 시작일 00:00 이상 조건을 전달한다")
    void getMeetingsWithFromOnly() {
        setUpListPermission();
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(List.of());

        meetingService.getMeetings(USER_ID, TEAM_ID, null, LocalDate.of(2026, 9, 15), null, null, 20);

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository).findMeetingsByCondition(conditionCaptor.capture());
        assertThat(conditionCaptor.getValue().fromInclusive()).isEqualTo(LocalDateTime.of(2026, 9, 15, 0, 0));
        assertThat(conditionCaptor.getValue().toExclusive()).isNull();
    }

    @Test
    @DisplayName("to만 있으면 종료일 다음날 00:00 미만 조건을 전달한다")
    void getMeetingsWithToOnly() {
        setUpListPermission();
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(List.of());

        meetingService.getMeetings(USER_ID, TEAM_ID, null, null, LocalDate.of(2026, 9, 15), null, 20);

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository).findMeetingsByCondition(conditionCaptor.capture());
        assertThat(conditionCaptor.getValue().fromInclusive()).isNull();
        assertThat(conditionCaptor.getValue().toExclusive()).isEqualTo(LocalDateTime.of(2026, 9, 16, 0, 0));
    }

    @Test
    @DisplayName("WAITING은 scheduledAt 기준으로 날짜 그룹을 만든다")
    void groupWaitingMeetingByScheduledAt() {
        setUpListPermission();
        Meeting meeting = meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 16, 9, 0), null);
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(List.of(meeting));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, 20);

        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 16));
    }

    @Test
    @DisplayName("IN_PROGRESS는 startedAt 기준으로 날짜 그룹을 만든다")
    void groupInProgressMeetingByStartedAt() {
        setUpListPermission();
        Meeting meeting = meeting(101L, MeetingStatus.IN_PROGRESS, LocalDateTime.of(2026, 9, 16, 9, 0),
                LocalDateTime.of(2026, 9, 15, 10, 0));
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(List.of(meeting));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, 20);

        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("COMPLETED는 startedAt 기준으로 날짜 그룹을 만든다")
    void groupCompletedMeetingByStartedAt() {
        setUpListPermission();
        Meeting meeting = meeting(101L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 9, 16, 9, 0),
                LocalDateTime.of(2026, 9, 15, 10, 0));
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(List.of(meeting));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, 20);

        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 15));
    }

    @Test
    @DisplayName("목록 조회는 다음 페이지가 있으면 nextCursor를 반환한다")
    void getMeetingsWithNextCursor() {
        setUpListPermission();
        Meeting first = meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 16, 9, 0), null);
        Meeting second = meeting(102L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 16, 14, 0), null);
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(List.of(first, second));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, 1);

        assertThat(response.hasNext()).isTrue();
        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).meetings()).hasSize(1);
        assertThat(MeetingCursor.decode(response.nextCursor()))
                .isEqualTo(new MeetingCursor(LocalDateTime.of(2026, 9, 16, 9, 0), 101L));
    }

    @Test
    @DisplayName("같은 날짜 그룹이 페이지 사이에서 나뉘어도 현재 페이지 group 개수만 반환한다")
    void getMeetingsWithSameDateGroupSplitByPage() {
        setUpListPermission();
        Meeting first = meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 15, 9, 0), null);
        Meeting second = meeting(102L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 15, 10, 0), null);
        Meeting third = meeting(103L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 15, 11, 0), null);
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(List.of(first, second, third));

        MeetingListResponse response = meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, 2);

        assertThat(response.hasNext()).isTrue();
        assertThat(response.groups()).hasSize(1);
        assertThat(response.groups().get(0).date()).isEqualTo(LocalDate.of(2026, 9, 15));
        assertThat(response.groups().get(0).meetingCount()).isEqualTo(2);
        assertThat(response.groups().get(0).meetings()).extracting("meetingId").containsExactly(101L, 102L);
        assertThat(MeetingCursor.decode(response.nextCursor()))
                .isEqualTo(new MeetingCursor(LocalDateTime.of(2026, 9, 15, 10, 0), 102L));
    }

    @Test
    @DisplayName("cursor가 있으면 목록 조회 조건으로 전달한다")
    void getMeetingsWithCursor() {
        setUpListPermission();
        MeetingCursor cursor = new MeetingCursor(LocalDateTime.of(2026, 9, 16, 9, 0), 101L);
        when(meetingRepository.findMeetingsByCondition(any())).thenReturn(List.of());

        meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, cursor.encode(), 20);

        ArgumentCaptor<MeetingListSearchCondition> conditionCaptor =
                ArgumentCaptor.forClass(MeetingListSearchCondition.class);
        verify(meetingRepository).findMeetingsByCondition(conditionCaptor.capture());
        assertThat(conditionCaptor.getValue().cursor()).isEqualTo(cursor);
    }

    @Test
    @DisplayName("존재하지 않는 팀이면 목록 조회에 실패한다")
    void getMeetingsWithNotFoundTeam() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(false);

        assertThatThrownBy(() -> meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, 20))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.TEAM_NOT_FOUND);

        verify(meetingRepository, never()).findMeetingsByCondition(any());
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

        assertThatThrownBy(() -> meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, 20))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.TEAM_MEMBERSHIP_REQUIRED);

        verify(meetingRepository, never()).findMeetingsByCondition(any());
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
                null,
                20
        ))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.INVALID_DATE_RANGE);
    }

    @Test
    @DisplayName("목록 조회 size가 0 이하 또는 50 초과이면 실패한다")
    void getMeetingsWithInvalidPageSize() {
        setUpListPermission();

        assertThatThrownBy(() -> meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, 0))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.INVALID_PAGE_SIZE);
        assertThatThrownBy(() -> meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, null, 51))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.INVALID_PAGE_SIZE);
    }

    @Test
    @DisplayName("잘못된 cursor이면 목록 조회에 실패한다")
    void getMeetingsWithInvalidCursor() {
        setUpListPermission();

        assertThatThrownBy(() -> meetingService.getMeetings(USER_ID, TEAM_ID, null, null, null, "invalid", 20))
                .isInstanceOf(MeetingException.class)
                .extracting("errorCode")
                .isEqualTo(MeetingErrorCode.INVALID_CURSOR);
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

    private void setUpListPermission() {
        when(teamRepository.existsById(TEAM_ID)).thenReturn(true);
        when(teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                TEAM_ID,
                USER_ID,
                MembershipStatus.ACTIVE
        )).thenReturn(true);
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
