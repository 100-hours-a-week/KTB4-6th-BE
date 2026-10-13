package com.backend.meety.domain.dashboard.dto;

import java.time.LocalDate;

public record DashboardChartPointResponse(
        LocalDate startDate,
        LocalDate endDate,
        Long targetDurationSeconds,
        Long actualDurationSeconds
) {
}
