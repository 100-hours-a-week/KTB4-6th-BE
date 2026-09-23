package com.backend.meety.domain.ai.realtime;

import tools.jackson.databind.JsonNode;

public record AiTranscriptSegmentPayload(
        String sourceSegmentKey,
        Long sequenceNumber,
        Long startMs,
        Long endMs,
        String text,
        String provider,
        String speakerId
) {

    public static AiTranscriptSegmentPayload from(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            throw new IllegalArgumentException("payload is missing");
        }
        String sourceSegmentKey = requiredText(payload, "sourceSegmentKey");
        Long sequenceNumber = requiredLong(payload, "sequenceNumber");
        Long startMs = requiredLong(payload, "startMs");
        Long endMs = requiredLong(payload, "endMs");
        if (startMs < 0 || endMs < 0 || endMs < startMs) {
            throw new IllegalArgumentException("startMs/endMs is invalid");
        }
        String text = requiredText(payload, "text");
        return new AiTranscriptSegmentPayload(
                sourceSegmentKey,
                sequenceNumber,
                startMs,
                endMs,
                text,
                optionalText(payload, "provider"),
                optionalText(payload, "speakerId")
        );
    }

    private static String requiredText(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.path(fieldName).isNull()) {
            throw new IllegalArgumentException(fieldName + " is missing");
        }
        String value = node.path(fieldName).asText();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " is blank");
        }
        return value;
    }

    private static Long requiredLong(JsonNode node, String fieldName) {
        String value = requiredText(node, fieldName);
        try {
            return Long.valueOf(value);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(fieldName + " is invalid", e);
        }
    }

    private static String optionalText(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.path(fieldName).isNull()) {
            return null;
        }
        String value = node.path(fieldName).asText();
        return value == null || value.isBlank() ? null : value;
    }
}
