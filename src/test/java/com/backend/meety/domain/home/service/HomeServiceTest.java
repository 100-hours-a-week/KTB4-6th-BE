package com.backend.meety.domain.home.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.home.dto.HomeMeetingDisplayStatus;
import com.backend.meety.domain.home.dto.HomeResponse;
import com.backend.meety.domain.home.exception.HomeErrorCode;
import com.backend.meety.domain.home.exception.HomeException;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.repository.MeetingMetricRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository.CompletedMeetingSummaryAggregate;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamInvitationCode;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamInvitationCodeRepository;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.user.entity.User;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class HomeServiceTest {

    private static final Long USER_ID = 1L;
    private static final Long TEAM_ID = 2L;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-09-17T03:00:00Z"),
            ZoneId.of("Asia/Seoul")
    );

    private TeamMemberRepository teamMemberRepository;
    private TeamInvitationCodeRepository teamInvitationCodeRepository;
    private MeetingRepository meetingRepository;
    private MeetingMetricRepository meetingMetricRepository;
    private HomeService homeService;

    @BeforeEach
    void setUp() {
        teamMemberRepository = mock(TeamMemberRepository.class);
        teamInvitationCodeRepository = mock(TeamInvitationCodeRepository.class);
        meetingRepository = mock(MeetingRepository.class);
        meetingMetricRepository = mock(MeetingMetricRepository.class);
        homeService = new HomeService(
                teamMemberRepository,
                teamInvitationCodeRepository,
                meetingRepository,
                meetingMetricRepository,
                FIXED_CLOCK
        );
    }

    @Test
    @DisplayName("ACTIVE 팀원이 없으면 ACTIVE_TEAM_REQUIRED 예외를 던진다")
    void getHomeWithoutActiveTeam() {
        when(teamMemberRepository.findByUserIdAndMembershipStatusWithTeam(USER_ID, MembershipStatus.ACTIVE))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> homeService.getHome(USER_ID))
                .isInstanceOf(HomeException.class)
                .extracting("errorCode")
                .isEqualTo(HomeErrorCode.ACTIVE_TEAM_REQUIRED);

        verify(teamInvitationCodeRepository, never()).findByTeamIdAndDeletedAtIsNull(any());
        verify(meetingRepository, never()).aggregateCompletedMeetingSummary(any());
    }

    @Test
    @DisplayName("홈 응답은 팀 정보, 종료 회의 집계, 지표 평균, 오늘 회의를 조합한다")
    void getHome() {
        Team team = team();
        TeamMember teamMember = activeTeamMember(team);
        TeamInvitationCode invitationCode = TeamInvitationCode.create(team, "ABCD1234");
        List<Meeting> todayMeetings = List.of(
                meeting(101L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 17, 11, 0), null, null),
                meeting(102L, MeetingStatus.WAITING, LocalDateTime.of(2026, 9, 17, 16, 0), null, null),
                meeting(103L, MeetingStatus.IN_PROGRESS, LocalDateTime.of(2026, 9, 17, 14, 0),
                        LocalDateTime.of(2026, 9, 17, 14, 3), null),
                meeting(104L, MeetingStatus.COMPLETED, LocalDateTime.of(2026, 9, 17, 9, 0),
                        LocalDateTime.of(2026, 9, 17, 9, 0), LocalDateTime.of(2026, 9, 17, 10, 0))
        );

        when(teamMemberRepository.findByUserIdAndMembershipStatusWithTeam(USER_ID, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(teamMember));
        when(teamInvitationCodeRepository.findByTeamIdAndDeletedAtIsNull(TEAM_ID))
                .thenReturn(Optional.of(invitationCode));
        when(teamMemberRepository.countByTeamIdAndMembershipStatus(TEAM_ID, MembershipStatus.ACTIVE))
                .thenReturn(5L);
        when(meetingRepository.aggregateCompletedMeetingSummary(TEAM_ID))
                .thenReturn(summary(12L, 530L));
        when(meetingMetricRepository.averageSpeechBalanceScoreByTeamId(TEAM_ID))
                .thenReturn(new BigDecimal("78.35"));
        when(meetingRepository.findTodayMeetingsByTeamIdAndEffectiveStartAtBetween(
                TEAM_ID,
                LocalDateTime.of(2026, 9, 17, 0, 0),
                LocalDateTime.of(2026, 9, 18, 0, 0)
        )).thenReturn(todayMeetings);

        HomeResponse response = homeService.getHome(USER_ID);

        assertThat(response.team().teamId()).isEqualTo(TEAM_ID);
        assertThat(response.team().name()).isEqualTo("Meety Team");
        assertThat(response.team().invitationCode()).isEqualTo("ABCD1234");
        assertThat(response.team().memberCount()).isEqualTo(5L);
        assertThat(response.meetingSummary().totalMeetingCount()).isEqualTo(12L);
        assertThat(response.meetingSummary().totalMeetingMinutes()).isEqualTo(530L);
        assertThat(response.meetingMetrics().averageMeetingMinutes()).isEqualTo(44L);
        assertThat(response.meetingMetrics().speechBalanceScore()).isEqualByComparingTo("78.35");
        assertThat(response.todayMeetingCount()).isEqualTo(response.todayMeetings().size());
        assertThat(response.todayMeetings()).extracting("meetingId")
                .containsExactly(101L, 102L, 103L, 104L);
        assertThat(response.todayMeetings()).extracting("status")
                .containsExactly(
                        HomeMeetingDisplayStatus.WAITING,
                        HomeMeetingDisplayStatus.SCHEDULED,
                        HomeMeetingDisplayStatus.IN_PROGRESS,
                        HomeMeetingDisplayStatus.COMPLETED
                );
    }

    @Test
    @DisplayName("완료 회의와 지표와 오늘 회의가 없으면 빈 데이터 정책을 따른다")
    void getHomeWithEmptyData() {
        Team team = team();
        TeamMember teamMember = activeTeamMember(team);

        when(teamMemberRepository.findByUserIdAndMembershipStatusWithTeam(USER_ID, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(teamMember));
        when(teamInvitationCodeRepository.findByTeamIdAndDeletedAtIsNull(TEAM_ID))
                .thenReturn(Optional.empty());
        when(teamMemberRepository.countByTeamIdAndMembershipStatus(TEAM_ID, MembershipStatus.ACTIVE))
                .thenReturn(1L);
        when(meetingRepository.aggregateCompletedMeetingSummary(TEAM_ID))
                .thenReturn(summary(0L, 0L));
        when(meetingMetricRepository.averageSpeechBalanceScoreByTeamId(TEAM_ID))
                .thenReturn(null);
        when(meetingRepository.findTodayMeetingsByTeamIdAndEffectiveStartAtBetween(any(), any(), any()))
                .thenReturn(List.of());

        HomeResponse response = homeService.getHome(USER_ID);

        assertThat(response.team().invitationCode()).isNull();
        assertThat(response.meetingSummary().totalMeetingCount()).isZero();
        assertThat(response.meetingSummary().totalMeetingMinutes()).isZero();
        assertThat(response.meetingMetrics().averageMeetingMinutes()).isNull();
        assertThat(response.meetingMetrics().speechBalanceScore()).isNull();
        assertThat(response.todayMeetingCount()).isZero();
        assertThat(response.todayMeetings()).isEmpty();
    }

    private Team team() {
        Team team = Team.create("Meety Team");
        ReflectionTestUtils.setField(team, "id", TEAM_ID);
        return team;
    }

    private TeamMember activeTeamMember(Team team) {
        User user = User.create();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        TeamMember teamMember = TeamMember.createLeader(user, team, "jay");
        ReflectionTestUtils.setField(teamMember, "id", 10L);
        return teamMember;
    }

    private Meeting meeting(
            Long meetingId,
            MeetingStatus status,
            LocalDateTime scheduledAt,
            LocalDateTime startedAt,
            LocalDateTime endedAt
    ) {
        Team team = team();
        TeamMember createdBy = activeTeamMember(team);
        Meeting meeting = Meeting.create(team, createdBy, "회의 " + meetingId, "목적", null, scheduledAt, 30);
        ReflectionTestUtils.setField(meeting, "id", meetingId);
        ReflectionTestUtils.setField(meeting, "status", status);
        ReflectionTestUtils.setField(meeting, "startedAt", startedAt);
        ReflectionTestUtils.setField(meeting, "endedAt", endedAt);
        return meeting;
    }

    private CompletedMeetingSummaryAggregate summary(Long totalMeetingCount, Long totalMeetingMinutes) {
        return new CompletedMeetingSummaryAggregate() {
            @Override
            public Long getTotalMeetingCount() {
                return totalMeetingCount;
            }

            @Override
            public Long getTotalMeetingMinutes() {
                return totalMeetingMinutes;
            }
        };
    }
}
