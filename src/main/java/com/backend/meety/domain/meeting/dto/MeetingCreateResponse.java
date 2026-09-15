package com.backend.meety.domain.meeting.dto;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import java.time.LocalDateTime;

public record MeetingCreateResponse(
        Long meetingId,
        Long teamId,
        Long createdByTeamMemberId,
        String title,
        String purpose,
        String note,
        LocalDateTime scheduledAt,
        Integer targetDurationMinutes,
        MeetingStatus status,
        LocalDateTime createdAt
) {

    public static MeetingCreateResponse from(Meeting meeting) {
        return new MeetingCreateResponse(
                meeting.getId(),
                meeting.getTeam().getId(),
                meeting.getCreatedByTeamMember().getId(),
                meeting.getTitle(),
                meeting.getPurpose(),
                meeting.getNote(),
                meeting.getScheduledAt(),
                meeting.getTargetDurationMinutes(),
                meeting.getStatus(),
                meeting.getCreatedAt()
        );
    }
}
