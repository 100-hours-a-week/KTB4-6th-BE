package com.backend.meety.domain.meeting.event;

public record SummaryReadyEvent(
        Long teamId,
        Long meetingId,
        Long aiRequestId,
        String meetingTitle
) {
}
