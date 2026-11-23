package com.backend.meety.domain.transcript.service;

import com.backend.meety.domain.ai.client.DiarizationAiResponse;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.messaging.AiResultHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class DiarizationResultHandler implements AiResultHandler {

    private final DiarizationService diarizationService;
    private final ObjectMapper objectMapper;

    @Override
    public AiRequestType requestType() {
        return AiRequestType.DIARIZATION;
    }

    @Override
    public boolean handleSucceeded(Long aiRequestId, JsonNode result) {
        DiarizationAiResponse response;
        try {
            response = objectMapper.treeToValue(result, DiarizationAiResponse.class);
        } catch (JacksonException e) {
            log.error("AI 화자 분리 결과를 해석할 수 없습니다. aiRequestId={}", aiRequestId, e);
            return false;
        }
        if (response.segments() == null) {
            log.error("AI 화자 분리 결과에 구간 목록이 없습니다. aiRequestId={}", aiRequestId);
            return false;
        }
        return diarizationService.completeDiarization(aiRequestId, response);
    }

    @Override
    public void handleFinalFailure(Long aiRequestId) {
    }
}
