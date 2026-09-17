package com.backend.meety.domain.home.dto;

public record HomeMeetingSummaryResponse(
        long totalMeetingCount,
        long totalMeetingMinutes
) {
}
