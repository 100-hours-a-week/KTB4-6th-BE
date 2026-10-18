package com.backend.meety.domain.meeting.realtime;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "meeting-sse.queue")
public record MeetingSseQueueProperties(int maxPendingPerMeeting, int maxPendingTotal) {

    public MeetingSseQueueProperties {
        if (maxPendingPerMeeting < 1 || maxPendingTotal < maxPendingPerMeeting) {
            throw new IllegalArgumentException(
                    "meeting-sse.queue limits must be positive and max-pending-total must be at least max-pending-per-meeting");
        }
    }
}
