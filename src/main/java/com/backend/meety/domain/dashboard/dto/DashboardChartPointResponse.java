package com.backend.meety.domain.dashboard.dto;

import java.time.LocalDate;

public record DashboardChartPointResponse(
        LocalDate startDate,
        LocalDate endDate,
        Long meetingCount,
        Long targetDurationSeconds,
        Long actualDurationSeconds,
        Long overtimeMeetings
) {
}
