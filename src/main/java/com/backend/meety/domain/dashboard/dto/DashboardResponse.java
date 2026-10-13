package com.backend.meety.domain.dashboard.dto;

import java.time.LocalDate;
import java.util.List;

public record DashboardResponse(
        Integer periodDays,
        LocalDate startDate,
        LocalDate endDate,
        DashboardSummaryResponse summary,
        List<DashboardChartPointResponse> chart,
        List<DashboardTopicResponse> topics
) {
}
