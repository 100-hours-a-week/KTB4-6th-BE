package com.backend.meety.domain.ai.realtime;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import tools.jackson.databind.JsonNode;

public record AiTranscriptSegmentPayload(
        Long sequenceNumber,
        String content,
        Long startedAtMs,
        Long endedAtMs,
        LocalDateTime recognizedAt
) {

    public static AiTranscriptSegmentPayload from(JsonNode payload) {
        if (payload == null || payload.isMissingNode() || payload.isNull()) {
            throw new IllegalArgumentException("payload is missing");
        }
        Long sequenceNumber = requiredLong(payload, "sequenceNumber");
        String content = requiredText(payload, "content");
        Long startedAtMs = requiredLong(payload, "startedAtMs");
        Long endedAtMs = optionalLong(payload, "endedAtMs");
        if (startedAtMs < 0 || (endedAtMs != null && (endedAtMs < 0 || endedAtMs < startedAtMs))) {
            throw new IllegalArgumentException("startedAtMs/endedAtMs is invalid");
        }
        return new AiTranscriptSegmentPayload(
                sequenceNumber,
                content,
                startedAtMs,
                endedAtMs,
                requiredRecognizedAt(payload)
        );
    }

    private static LocalDateTime requiredRecognizedAt(JsonNode payload) {
        String value = requiredText(payload, "recognizedAt");
        try {
            return OffsetDateTime.parse(value).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("recognizedAt is invalid", e);
        }
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

    private static Long optionalLong(JsonNode node, String fieldName) {
        if (!node.has(fieldName) || node.path(fieldName).isNull()) {
            return null;
        }
        return requiredLong(node, fieldName);
    }
}
