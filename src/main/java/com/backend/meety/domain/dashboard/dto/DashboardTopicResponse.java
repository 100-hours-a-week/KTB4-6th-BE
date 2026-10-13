package com.backend.meety.domain.dashboard.dto;

import java.math.BigDecimal;

public record DashboardTopicResponse(
        String name,
        BigDecimal durationSeconds
) {
}
