package com.backend.meety.domain.meeting.dto;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import java.time.LocalDateTime;

public record MeetingCalendarItemResponse(
        Long meetingId,
        String title,
        MeetingStatus status,
        LocalDateTime scheduledAt,
        LocalDateTime startedAt,
        LocalDateTime endedAt
) {

    public static MeetingCalendarItemResponse from(Meeting meeting) {
        return new MeetingCalendarItemResponse(
                meeting.getId(),
                meeting.getTitle(),
                meeting.getStatus(),
                meeting.getScheduledAt(),
                meeting.getStartedAt(),
                meeting.getEndedAt()
        );
    }
}
