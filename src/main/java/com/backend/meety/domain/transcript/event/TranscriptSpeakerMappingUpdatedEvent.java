package com.backend.meety.domain.transcript.event;

import java.util.List;

public record TranscriptSpeakerMappingUpdatedEvent(Long meetingId, Long recordingSessionId, List<Mapping> mappings) {
    public record Mapping(Long speakerId, Long teamMemberId, String displayName) { }
}
