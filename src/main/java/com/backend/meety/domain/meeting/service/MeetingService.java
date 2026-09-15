package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.meeting.dto.MeetingCreateRequest;
import com.backend.meety.domain.meeting.dto.MeetingCreateResponse;
import com.backend.meety.domain.meeting.dto.MeetingDetailResponse;
import com.backend.meety.domain.meeting.dto.MeetingGroupResponse;
import com.backend.meety.domain.meeting.dto.MeetingListItemResponse;
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
    private static final int DEFAULT_PAGE_SIZE = 20;
    private static final int MAX_PAGE_SIZE = 50;

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

    @Transactional(readOnly = true)
    public MeetingDetailResponse getMeeting(Long userId, Long meetingId) {
        Meeting meeting = meetingRepository.findDetailByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));

        validateMeetingAccess(userId, meeting.getTeam().getId());

        return MeetingDetailResponse.from(meeting);
    }

    @Transactional(readOnly = true)
    public MeetingListResponse getMeetings(
            Long userId,
            Long teamId,
            String keyword,
            LocalDate from,
            LocalDate to,
            String cursor,
            Integer size
    ) {
        validateTeamExists(teamId);
        validateTeamMembership(userId, teamId, MeetingErrorCode.TEAM_MEMBERSHIP_REQUIRED);
        validateDateRange(from, to);

        int pageSize = resolvePageSize(size);
        MeetingCursor meetingCursor = MeetingCursor.decode(cursor);
        String normalizedKeyword = normalizeKeyword(keyword);

        List<Meeting> meetings = meetingRepository.findMeetingsByCondition(new MeetingListSearchCondition(
                teamId,
                normalizedKeyword,
                toStartOfDay(from),
                toExclusiveStartOfDay(to),
                meetingCursor,
                pageSize + 1
        ));

        boolean hasNext = meetings.size() > pageSize;
        List<Meeting> pageMeetings = hasNext ? meetings.subList(0, pageSize) : meetings;
        String nextCursor = hasNext ? toCursor(pageMeetings.get(pageMeetings.size() - 1)) : null;

        return new MeetingListResponse(toGroups(pageMeetings), nextCursor, hasNext);
    }

    private void validateMeetingAccess(Long userId, Long teamId) {
        validateTeamMembership(userId, teamId, MeetingErrorCode.MEETING_ACCESS_DENIED);
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

    private int resolvePageSize(Integer size) {
        if (size == null) {
            return DEFAULT_PAGE_SIZE;
        }
        if (size <= 0 || size > MAX_PAGE_SIZE) {
            throw new MeetingException(MeetingErrorCode.INVALID_PAGE_SIZE);
        }
        return size;
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

    private String toCursor(Meeting meeting) {
        return new MeetingCursor(effectiveStartAt(meeting), meeting.getId()).encode();
    }

    private LocalDateTime effectiveStartAt(Meeting meeting) {
        if (meeting.getStatus() == MeetingStatus.WAITING) {
            return meeting.getScheduledAt();
        }
        return meeting.getStartedAt();
    }
}
