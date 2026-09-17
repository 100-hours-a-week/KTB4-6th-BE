package com.backend.meety.domain.home.dto;

import java.math.BigDecimal;

public record HomeMeetingMetricsResponse(
        Long averageMeetingMinutes,
        BigDecimal speechBalanceScore
) {
}
