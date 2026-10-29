package com.backend.meety.domain.ai.realtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

public record AiSpeakerMappingMessage(
        Long meetingId,
        Long recordingSessionId,
        List<Mapping> mappings
) {
    public static final String TYPE = "speaker.mapping.updated";

    public record Mapping(Long speakerId, Long teamMemberId) { }

    public static AiSpeakerMappingMessage from(JsonNode root) {
        Long meetingId = requiredLong(root, "meetingId");
        Long recordingSessionId = requiredLong(root, "recordingSessionId");
        JsonNode payload = root.path("payload");
        JsonNode values = payload.path("mappings");
        if (!values.isArray()) {
            throw new IllegalArgumentException("speaker mappings must be an array");
        }
        List<Mapping> mappings = new ArrayList<>();
        Set<Long> speakerIds = new HashSet<>();
        Set<Long> memberIds = new HashSet<>();
        for (JsonNode value : values) {
            Long speakerId = requiredLong(value, "speakerId");
            Long teamMemberId = optionalLong(value, "teamMemberId");
            if (speakerId < 0 || !speakerIds.add(speakerId)) {
                throw new IllegalArgumentException("speakerId is negative or duplicated");
            }
            if (teamMemberId != null && !memberIds.add(teamMemberId)) {
                throw new IllegalArgumentException("teamMemberId is duplicated");
            }
            mappings.add(new Mapping(speakerId, teamMemberId));
        }
        return new AiSpeakerMappingMessage(meetingId, recordingSessionId, List.copyOf(mappings));
    }

    private static Long requiredLong(JsonNode node, String field) {
        Long value = optionalLong(node, field);
        if (value == null) { throw new IllegalArgumentException(field + " is missing or null"); }
        return value;
    }

    private static Long optionalLong(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isMissingNode() || value.isNull()) { return null; }
        if (!value.canConvertToLong()) { throw new IllegalArgumentException(field + " is invalid"); }
        return value.longValue();
    }
}
