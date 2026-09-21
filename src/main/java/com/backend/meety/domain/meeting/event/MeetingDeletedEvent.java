package com.backend.meety.domain.meeting.event;

public record MeetingDeletedEvent(
        Long meetingId
) {
}
