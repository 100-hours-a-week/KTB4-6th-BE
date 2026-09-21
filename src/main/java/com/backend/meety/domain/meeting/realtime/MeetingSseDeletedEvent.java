package com.backend.meety.domain.meeting.realtime;

public record MeetingSseDeletedEvent(
        String type,
        Long meetingId
) {
    public static MeetingSseDeletedEvent deleted(Long meetingId) {
        return new MeetingSseDeletedEvent("MEETING_DELETED", meetingId);
    }
}
