package com.backend.meety.domain.home.service;

import com.backend.meety.domain.home.dto.HomeMeetingMetricsResponse;
import com.backend.meety.domain.home.dto.HomeMeetingSummaryResponse;
import com.backend.meety.domain.home.dto.HomeResponse;
import com.backend.meety.domain.home.dto.HomeTeamResponse;
import com.backend.meety.domain.home.dto.HomeTodayMeetingResponse;
import com.backend.meety.domain.home.exception.HomeErrorCode;
import com.backend.meety.domain.home.exception.HomeException;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingMetricRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository.CompletedMeetingSummaryAggregate;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamInvitationCode;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamInvitationCodeRepository;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class HomeService {

    private static final ZoneId KST_ZONE_ID = ZoneId.of("Asia/Seoul");

    private final TeamMemberRepository teamMemberRepository;
    private final TeamInvitationCodeRepository teamInvitationCodeRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingMetricRepository meetingMetricRepository;
    private final Clock clock;

    @Transactional(readOnly = true)
    public HomeResponse getHome(Long userId) {
        TeamMember activeTeamMember = teamMemberRepository
                .findByUserIdAndMembershipStatusWithTeam(userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new HomeException(HomeErrorCode.ACTIVE_TEAM_REQUIRED));
        Team team = activeTeamMember.getTeam();
        Long teamId = team.getId();

        HomeTeamResponse teamResponse = new HomeTeamResponse(
                teamId,
                team.getName(),
                findActiveInvitationCode(teamId),
                teamMemberRepository.countByTeamIdAndMembershipStatus(teamId, MembershipStatus.ACTIVE)
        );

        CompletedMeetingSummaryAggregate summary = meetingRepository.aggregateCompletedMeetingSummary(teamId);
        long totalMeetingCount = valueOrZero(summary.getTotalMeetingCount());
        long totalMeetingMinutes = valueOrZero(summary.getTotalMeetingMinutes());
        HomeMeetingSummaryResponse meetingSummary = new HomeMeetingSummaryResponse(
                totalMeetingCount,
                totalMeetingMinutes
        );

        HomeMeetingMetricsResponse meetingMetrics = new HomeMeetingMetricsResponse(
                calculateAverageMeetingMinutes(totalMeetingCount, totalMeetingMinutes),
                meetingMetricRepository.averageSpeechBalanceScoreByTeamId(teamId)
        );

        LocalDate today = LocalDate.now(KST_ZONE_ID);
        List<Meeting> todayMeetings = meetingRepository.findTodayMeetingsByTeamIdAndEffectiveStartAtBetween(
                teamId,
                today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()
        );
        LocalDateTime now = LocalDateTime.now(clock.withZone(KST_ZONE_ID));
        List<HomeTodayMeetingResponse> todayMeetingResponses = todayMeetings.stream()
                .map(meeting -> HomeTodayMeetingResponse.from(meeting, now))
                .toList();

        return HomeResponse.of(teamResponse, meetingSummary, meetingMetrics, todayMeetingResponses);
    }

    private String findActiveInvitationCode(Long teamId) {
        return teamInvitationCodeRepository.findByTeamIdAndDeletedAtIsNull(teamId)
                .map(TeamInvitationCode::getCode)
                .orElse(null);
    }

    private long valueOrZero(Long value) {
        return value == null ? 0L : value;
    }

    private Long calculateAverageMeetingMinutes(long totalMeetingCount, long totalMeetingMinutes) {
        if (totalMeetingCount == 0) {
            return null;
        }
        return totalMeetingMinutes / totalMeetingCount;
    }
}
