package com.backend.meety.domain.ai.realtime;

import tools.jackson.databind.JsonNode;

public record AiTranscriptSegmentMessage(
        String type,
        String eventId,
        Long meetingId,
        Long recordingSessionId,
        AiTranscriptSegmentPayload payload
) {

    public static final String TYPE = "transcript.segment.final";

    public static AiTranscriptSegmentMessage from(JsonNode root) {
        if (!TYPE.equals(root.path("type").asText())) {
            throw new IllegalArgumentException("unsupported transcript event type");
        }
        String eventId = requiredText(root, "eventId");
        Long meetingId = requiredLong(root, "meetingId");
        Long recordingSessionId = requiredLong(root, "recordingSessionId");
        AiTranscriptSegmentPayload payload = AiTranscriptSegmentPayload.from(root.path("payload"));
        return new AiTranscriptSegmentMessage(TYPE, eventId, meetingId, recordingSessionId, payload);
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
}
