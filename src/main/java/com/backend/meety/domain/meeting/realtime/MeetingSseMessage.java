package com.backend.meety.domain.meeting.realtime;

import tools.jackson.databind.JsonNode;

public record MeetingSseMessage(
        MeetingSseCommand command,
        Long meetingId,
        Long userId,
        String eventName,
        JsonNode payload
) {
}
