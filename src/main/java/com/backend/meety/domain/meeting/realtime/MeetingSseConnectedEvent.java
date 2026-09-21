package com.backend.meety.domain.meeting.realtime;

public record MeetingSseConnectedEvent(
        String type,
        Long meetingId
) {

    public static MeetingSseConnectedEvent connected(Long meetingId) {
        return new MeetingSseConnectedEvent("CONNECTED", meetingId);
    }
}
