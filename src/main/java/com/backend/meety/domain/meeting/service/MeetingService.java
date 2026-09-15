package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.meeting.dto.MeetingCreateRequest;
import com.backend.meety.domain.meeting.dto.MeetingCreateResponse;
import com.backend.meety.domain.meeting.entity.Meeting;
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
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingService {

    private static final ZoneId KST_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final long DAILY_MEETING_LIMIT = 5L;

    private final MeetingRepository meetingRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;

    @Transactional
    public MeetingCreateResponse createMeeting(Long userId, Long teamId, MeetingCreateRequest request) {
        Team team = teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.TEAM_NOT_FOUND));
        validateDailyMeetingLimit(teamId);

        TeamMember teamMember = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        teamId,
                        userId,
                        MembershipStatus.ACTIVE
                )
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.TEAM_MEMBERSHIP_REQUIRED));

        Meeting meeting = Meeting.create(
                team,
                teamMember,
                request.title(),
                request.purpose(),
                request.note(),
                request.scheduledAt(),
                request.targetDurationMinutes()
        );

        try {
            return MeetingCreateResponse.from(meetingRepository.saveAndFlush(meeting));
        } catch (DataAccessException e) {
            throw new MeetingException(MeetingErrorCode.MEETING_CREATE_FAILED);
        }
    }

    private void validateDailyMeetingLimit(Long teamId) {
        LocalDate today = LocalDate.now(KST_ZONE_ID);
        LocalDateTime startOfDay = today.atStartOfDay();
        LocalDateTime nextDay = today.plusDays(1).atStartOfDay();

        long todayMeetingCount = meetingRepository.countCreatedTodayByTeamId(
                teamId,
                startOfDay,
                nextDay
        );

        if (todayMeetingCount >= DAILY_MEETING_LIMIT) {
            throw new MeetingException(MeetingErrorCode.DAILY_MEETING_LIMIT_EXCEEDED);
        }
    }
}
