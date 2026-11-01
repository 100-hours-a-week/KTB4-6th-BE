package com.backend.meety.domain.ai.dto;

import tools.jackson.databind.JsonNode;
import java.math.BigDecimal;

public record InternalMeetingMetricsResponse(JsonNode metrics, BigDecimal speechBalanceScore,
                                            Long contextOutDurationMs) {
}
