package com.backend.meety.domain.home.dto;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import java.time.LocalDateTime;

public record HomeTodayMeetingResponse(
        Long meetingId,
        String title,
        HomeMeetingDisplayStatus status,
        LocalDateTime scheduledAt,
        LocalDateTime startedAt,
        LocalDateTime endedAt
) {

    public static HomeTodayMeetingResponse from(Meeting meeting) {
        return new HomeTodayMeetingResponse(
                meeting.getId(),
                meeting.getTitle(),
                toDisplayStatus(meeting),
                meeting.getScheduledAt(),
                meeting.getStartedAt(),
                meeting.getEndedAt()
        );
    }

    private static HomeMeetingDisplayStatus toDisplayStatus(Meeting meeting) {
        if (meeting.getStatus() == MeetingStatus.WAITING) {
            return HomeMeetingDisplayStatus.WAITING;
        }
        if (meeting.getStatus() == MeetingStatus.IN_PROGRESS) {
            return HomeMeetingDisplayStatus.IN_PROGRESS;
        }
        return HomeMeetingDisplayStatus.COMPLETED;
    }
}
