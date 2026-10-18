package com.backend.meety.domain.dashboard.dto;

import java.math.BigDecimal;

public record DashboardSummaryResponse(
        Long totalMeetings,
        Long totalMeetingDurationSeconds,
        Long averageMeetingDurationSeconds,
        Long overtimeMeetings,
        BigDecimal overtimeMeetingRate,
        BigDecimal totalContextOutSeconds,
        BigDecimal contextOutRate
) {
}
