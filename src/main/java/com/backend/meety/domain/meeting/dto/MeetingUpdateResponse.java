package com.backend.meety.domain.meeting.dto;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import java.time.LocalDateTime;

public record MeetingUpdateResponse(
        Long meetingId,
        Long teamId,
        Long createdByTeamMemberId,
        String title,
        String purpose,
        String note,
        LocalDateTime scheduledAt,
        Integer targetDurationMinutes,
        MeetingStatus status,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        LocalDateTime updatedAt
) {

    public static MeetingUpdateResponse from(Meeting meeting) {
        return new MeetingUpdateResponse(
                meeting.getId(),
                meeting.getTeam().getId(),
                meeting.getCreatedByTeamMember().getId(),
                meeting.getTitle(),
                meeting.getPurpose(),
                meeting.getNote(),
                meeting.getScheduledAt(),
                meeting.getTargetDurationMinutes(),
                meeting.getStatus(),
                meeting.getStartedAt(),
                meeting.getEndedAt(),
                meeting.getUpdatedAt()
        );
    }
}
