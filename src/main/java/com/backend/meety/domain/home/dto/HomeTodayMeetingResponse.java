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

    public static HomeTodayMeetingResponse from(Meeting meeting, LocalDateTime now) {
        return new HomeTodayMeetingResponse(
                meeting.getId(),
                meeting.getTitle(),
                toDisplayStatus(meeting, now),
                meeting.getScheduledAt(),
                meeting.getStartedAt(),
                meeting.getEndedAt()
        );
    }

    private static HomeMeetingDisplayStatus toDisplayStatus(Meeting meeting, LocalDateTime now) {
        if (meeting.getStatus() == MeetingStatus.WAITING && now.isBefore(meeting.getScheduledAt())) {
            return HomeMeetingDisplayStatus.SCHEDULED;
        }
        if (meeting.getStatus() == MeetingStatus.WAITING) {
            return HomeMeetingDisplayStatus.WAITING;
        }
        if (meeting.getStatus() == MeetingStatus.IN_PROGRESS) {
            return HomeMeetingDisplayStatus.IN_PROGRESS;
        }
        return HomeMeetingDisplayStatus.COMPLETED;
    }
}
