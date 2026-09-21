package com.backend.meety.domain.meeting.event;

public record MeetingParticipantLeftEvent(
        Long meetingId,
        Long userId
) {
}
