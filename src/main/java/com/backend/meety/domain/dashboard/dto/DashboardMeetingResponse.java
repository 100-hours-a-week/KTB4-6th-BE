package com.backend.meety.domain.dashboard.dto;

public record DashboardMeetingResponse(
        Long meetingId,
        String title,
        Long targetDurationSeconds,
        Long actualDurationSeconds
) {
}
