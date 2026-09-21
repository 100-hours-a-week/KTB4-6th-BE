package com.backend.meety.domain.meeting.realtime;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MeetingEventBroadcaster {

    private final MeetingSseRegistry meetingSseRegistry;

    public void broadcast(MeetingRealtimeEvent event) {
        meetingSseRegistry.broadcast(event.meetingId(), event);
    }

    public void broadcastAndComplete(MeetingRealtimeEvent event) {
        meetingSseRegistry.broadcast(event.meetingId(), event);
        meetingSseRegistry.completeMeeting(event.meetingId());
    }
}
