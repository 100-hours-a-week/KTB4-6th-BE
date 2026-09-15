package com.backend.meety.domain.meeting.dto;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import java.time.LocalDateTime;

public record MeetingListItemResponse(
        Long meetingId,
        String title,
        LocalDateTime scheduledAt,
        LocalDateTime startedAt,
        LocalDateTime endedAt,
        Integer targetDurationMinutes,
        MeetingStatus status
) {

    public static MeetingListItemResponse from(Meeting meeting) {
        return new MeetingListItemResponse(
                meeting.getId(),
                meeting.getTitle(),
                meeting.getScheduledAt(),
                meeting.getStartedAt(),
                meeting.getEndedAt(),
                meeting.getTargetDurationMinutes(),
                meeting.getStatus()
        );
    }
}
