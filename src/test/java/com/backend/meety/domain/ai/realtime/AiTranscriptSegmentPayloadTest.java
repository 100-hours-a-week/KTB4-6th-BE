package com.backend.meety.domain.ai.realtime;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class AiTranscriptSegmentPayloadTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("AI 전사 speakerId 0을 화자 식별자로 유지한다")
    void parsesZeroSpeakerId() {
        // 테스트 목적:
        // AI가 보내는 화자 식별자 0을 누락이나 null로 취급하지 않는지 검증한다.

        // given
        JsonNode payload = payloadWith("\"speakerId\": 0");

        // when
        AiTranscriptSegmentPayload result = AiTranscriptSegmentPayload.from(payload);

        // then
        assertThat(result.speakerId()).isZero();
    }

    @Test
    @DisplayName("AI 전사 speakerId가 누락되거나 null이어도 기존 전사를 수신한다")
    void acceptsMissingOrNullSpeakerId() {
        // 테스트 목적:
        // 화자 식별자가 없는 기존 AI 메시지와 명시적 null 메시지 모두 전사 수신을 유지하는지 검증한다.

        // given
        JsonNode missingSpeaker = payloadWith(null);
        JsonNode nullSpeaker = payloadWith("\"speakerId\": null");

        // when
        AiTranscriptSegmentPayload missingResult = AiTranscriptSegmentPayload.from(missingSpeaker);
        AiTranscriptSegmentPayload nullResult = AiTranscriptSegmentPayload.from(nullSpeaker);

        // then
        assertThat(missingResult.speakerId()).isNull();
        assertThat(nullResult.speakerId()).isNull();
    }

    private JsonNode payloadWith(String speakerField) {
        String json = """
                {
                  "sequenceNumber": 86,
                  "content": "회의 발화",
                  "startedAtMs": 398630,
                  "endedAtMs": 402620,
                  "recognizedAt": "2026-10-25T06:33:36.797435Z"
                }
                """;
        if (speakerField != null) {
            json = json.replace("\"recognizedAt\":", speakerField + ", \"recognizedAt\":");
        }
        return objectMapper.readTree(json);
    }
}
