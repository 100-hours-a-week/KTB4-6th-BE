package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.meeting.dto.MeetingCreateRequest;
import com.backend.meety.domain.meeting.dto.MeetingCreateResponse;
import com.backend.meety.domain.meeting.dto.MeetingCalendarDateResponse;
import com.backend.meety.domain.meeting.dto.MeetingCalendarItemResponse;
import com.backend.meety.domain.meeting.dto.MeetingCalendarResponse;
import com.backend.meety.domain.meeting.dto.MeetingDetailResponse;
import com.backend.meety.domain.meeting.dto.MeetingGroupResponse;
import com.backend.meety.domain.meeting.dto.MeetingListItemResponse;
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
import com.backend.meety.global.exception.BusinessException;
import com.backend.meety.global.exception.CommonErrorCode;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MeetingService {

    private static final ZoneId KST_ZONE_ID = ZoneId.of("Asia/Seoul");
    private static final long DAILY_MEETING_LIMIT = 5L;
    private static final int DATE_GROUP_LIMIT = 5;

    private final MeetingRepository meetingRepository;
    private final TeamRepository teamRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final Clock clock;

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
                LocalDateTime.now(clock.withZone(KST_ZONE_ID)),
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

    @Transactional(readOnly = true)
    public MeetingDetailResponse getMeeting(Long userId, Long meetingId) {
        Meeting meeting = meetingRepository.findDetailByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));

        validateMeetingAccess(userId, meeting.getTeam().getId());

        return MeetingDetailResponse.from(meeting);
    }

    @Transactional
    public MeetingUpdateResponse updateMeeting(Long userId, Long meetingId, MeetingUpdateRequest request) {
        Meeting meeting = meetingRepository.findByIdForUpdateAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        TeamMember teamMember = findActiveTeamMember(
                userId,
                meeting.getTeam().getId(),
                MeetingErrorCode.MEETING_UPDATE_FORBIDDEN
        );
        validateUpdatePermission(teamMember, meeting);
        validateMeetingUpdateAllowed(meeting, request);

        if (meeting.getStatus() == MeetingStatus.WAITING) {
            meeting.updateWaitingInfo(
                    request.title(),
                    request.purpose(),
                    request.note(),
                    request.scheduledAt(),
                    request.targetDurationMinutes()
            );
        } else if (meeting.getStatus() == MeetingStatus.COMPLETED) {
            meeting.rename(request.title());
        }

        return MeetingUpdateResponse.from(meeting);
    }

    @Transactional
    public void deleteMeeting(Long userId, Long meetingId) {
        Meeting meeting = meetingRepository.findByIdForUpdateAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        TeamMember teamMember = findActiveTeamMember(
                userId,
                meeting.getTeam().getId(),
                MeetingErrorCode.MEETING_DELETE_FORBIDDEN
        );
        validateDeletePermission(teamMember);
        validateMeetingDeleteAllowed(meeting);

        meeting.softDelete(LocalDateTime.now(clock));
    }

    @Transactional(readOnly = true)
    public MeetingListResponse getMeetings(
            Long userId,
            Long teamId,
            String keyword,
            LocalDate from,
            LocalDate to,
            String cursor
    ) {
        validateTeamExists(teamId);
        validateTeamMembership(userId, teamId, MeetingErrorCode.TEAM_MEMBERSHIP_REQUIRED);
        validateDateRange(from, to);

        MeetingCursor meetingCursor = MeetingCursor.decode(cursor);
        String normalizedKeyword = normalizeKeyword(keyword);

        MeetingListSearchCondition condition = new MeetingListSearchCondition(
                teamId,
                normalizedKeyword,
                toStartOfDay(from),
                toExclusiveStartOfDay(to),
                meetingCursor == null ? null : meetingCursor.date(),
                DATE_GROUP_LIMIT + 1
        );

        List<LocalDate> meetingDates = meetingRepository.findMeetingDatesByCondition(condition);
        boolean hasNext = meetingDates.size() > DATE_GROUP_LIMIT;
        List<LocalDate> pageDates = hasNext ? meetingDates.subList(0, DATE_GROUP_LIMIT) : meetingDates;

        if (pageDates.isEmpty()) {
            return new MeetingListResponse(List.of(), null, false);
        }

        List<Meeting> meetings = meetingRepository.findMeetingsByDates(condition, pageDates);
        String nextCursor = hasNext ? new MeetingCursor(pageDates.get(pageDates.size() - 1)).encode() : null;

        return new MeetingListResponse(toGroups(meetings), nextCursor, hasNext);
    }

    @Transactional(readOnly = true)
    public MeetingCalendarResponse getMeetingCalendar(Long userId, Long teamId, Integer year, Integer month) {
        validateTeamExists(teamId);
        validateTeamMembership(userId, teamId, MeetingErrorCode.TEAM_MEMBERSHIP_REQUIRED);

        LocalDateTime monthStart = toMonthStart(year, month);
        LocalDateTime nextMonthStart = monthStart.toLocalDate().plusMonths(1).atStartOfDay();
        List<Meeting> meetings = meetingRepository.findCalendarMeetingsByTeamIdAndEffectiveStartAtBetween(
                teamId,
                monthStart,
                nextMonthStart
        );

        return new MeetingCalendarResponse(year, month, toCalendarDates(meetings));
    }

    private void validateMeetingAccess(Long userId, Long teamId) {
        validateTeamMembership(userId, teamId, MeetingErrorCode.MEETING_ACCESS_DENIED);
    }

    private TeamMember findActiveTeamMember(Long userId, Long teamId, MeetingErrorCode errorCode) {
        return teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        teamId,
                        userId,
                        MembershipStatus.ACTIVE
                )
                .orElseThrow(() -> new MeetingException(errorCode));
    }

    private void validateUpdatePermission(TeamMember teamMember, Meeting meeting) {
        if (isLeader(teamMember) || teamMember.getId().equals(meeting.getCreatedByTeamMember().getId())) {
            return;
        }
        throw new MeetingException(MeetingErrorCode.MEETING_UPDATE_FORBIDDEN);
    }

    private void validateDeletePermission(TeamMember teamMember) {
        if (!isLeader(teamMember)) {
            throw new MeetingException(MeetingErrorCode.MEETING_DELETE_FORBIDDEN);
        }
    }

    private boolean isLeader(TeamMember teamMember) {
        return teamMember.getRole() == TeamMemberRole.LEADER;
    }

    private void validateMeetingUpdateAllowed(Meeting meeting, MeetingUpdateRequest request) {
        if (meeting.getStatus() == MeetingStatus.IN_PROGRESS) {
            throw new MeetingException(MeetingErrorCode.MEETING_FIELD_UPDATE_NOT_ALLOWED);
        }
        if (meeting.getStatus() == MeetingStatus.COMPLETED && request.hasForbiddenCompletedField()) {
            throw new MeetingException(MeetingErrorCode.MEETING_FIELD_UPDATE_NOT_ALLOWED);
        }
    }

    private void validateMeetingDeleteAllowed(Meeting meeting) {
        if (meeting.getStatus() == MeetingStatus.IN_PROGRESS) {
            throw new MeetingException(MeetingErrorCode.MEETING_IN_PROGRESS);
        }
    }

    private void validateTeamMembership(Long userId, Long teamId, MeetingErrorCode errorCode) {
        if (!teamMemberRepository.existsByTeamIdAndUserIdAndMembershipStatus(
                teamId,
                userId,
                MembershipStatus.ACTIVE
        )) {
            throw new MeetingException(errorCode);
        }
    }

    private void validateTeamExists(Long teamId) {
        if (!teamRepository.existsById(teamId)) {
            throw new MeetingException(MeetingErrorCode.TEAM_NOT_FOUND);
        }
    }

    private void validateDateRange(LocalDate from, LocalDate to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new MeetingException(MeetingErrorCode.INVALID_DATE_RANGE);
        }
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }

        String trimmedKeyword = keyword.trim();
        if (trimmedKeyword.isBlank()) {
            return null;
        }
        return trimmedKeyword;
    }

    private LocalDateTime toStartOfDay(LocalDate date) {
        return date == null ? null : date.atStartOfDay();
    }

    private LocalDateTime toExclusiveStartOfDay(LocalDate date) {
        return date == null ? null : date.plusDays(1).atStartOfDay();
    }

    private LocalDateTime toMonthStart(Integer year, Integer month) {
        try {
            return LocalDate.of(year, month, 1).atStartOfDay();
        } catch (DateTimeException e) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private List<MeetingCalendarDateResponse> toCalendarDates(List<Meeting> meetings) {
        List<MeetingCalendarDateResponse> dates = new ArrayList<>();
        LocalDate currentDate = null;
        List<MeetingCalendarItemResponse> currentMeetings = new ArrayList<>();

        for (Meeting meeting : meetings) {
            LocalDate meetingDate = effectiveStartAt(meeting).toLocalDate();
            if (currentDate != null && !currentDate.equals(meetingDate)) {
                dates.add(new MeetingCalendarDateResponse(currentDate, currentMeetings));
                currentMeetings = new ArrayList<>();
            }
            currentDate = meetingDate;
            currentMeetings.add(MeetingCalendarItemResponse.from(meeting));
        }

        if (currentDate != null) {
            dates.add(new MeetingCalendarDateResponse(currentDate, currentMeetings));
        }

        return dates;
    }

    private List<MeetingGroupResponse> toGroups(List<Meeting> meetings) {
        List<MeetingGroupResponse> groups = new ArrayList<>();
        LocalDate currentDate = null;
        List<MeetingListItemResponse> currentMeetings = new ArrayList<>();

        for (Meeting meeting : meetings) {
            LocalDate meetingDate = effectiveStartAt(meeting).toLocalDate();
            if (currentDate != null && !currentDate.equals(meetingDate)) {
                groups.add(MeetingGroupResponse.of(currentDate, currentMeetings));
                currentMeetings = new ArrayList<>();
            }
            currentDate = meetingDate;
            currentMeetings.add(MeetingListItemResponse.from(meeting));
        }

        if (currentDate != null) {
            groups.add(MeetingGroupResponse.of(currentDate, currentMeetings));
        }

        return groups;
    }

    private LocalDateTime effectiveStartAt(Meeting meeting) {
        if (meeting.getStatus() == MeetingStatus.WAITING) {
            return meeting.getScheduledAt();
        }
        return meeting.getStartedAt();
    }
}
