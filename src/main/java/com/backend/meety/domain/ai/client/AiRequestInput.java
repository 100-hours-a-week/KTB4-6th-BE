package com.backend.meety.domain.ai.client;

import com.backend.meety.domain.ai.entity.AiRequestType;

public sealed interface AiRequestInput
        permits SummaryAiRequest, MetricAiRequest, DiarizationAiRequest, ChatAiRequest {

    AiRequestType requestType();
}
