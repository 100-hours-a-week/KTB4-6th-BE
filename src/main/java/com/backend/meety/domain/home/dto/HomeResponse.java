package com.backend.meety.domain.home.dto;

import java.util.List;

public record HomeResponse(
        HomeTeamResponse team,
        HomeMeetingSummaryResponse meetingSummary,
        HomeMeetingMetricsResponse meetingMetrics,
        int todayMeetingCount,
        List<HomeTodayMeetingResponse> todayMeetings
) {

    public static HomeResponse of(
            HomeTeamResponse team,
            HomeMeetingSummaryResponse meetingSummary,
            HomeMeetingMetricsResponse meetingMetrics,
            List<HomeTodayMeetingResponse> todayMeetings
    ) {
        return new HomeResponse(
                team,
                meetingSummary,
                meetingMetrics,
                todayMeetings.size(),
                todayMeetings
        );
    }
}
