package com.backend.meety.domain.transcript.realtime;

import com.backend.meety.domain.transcript.event.TranscriptSpeakerMappingUpdatedEvent;
import java.util.List;

public record SpeakerMappingUpdatedSseEvent(String type, Long meetingId, Long recordingSessionId, List<Mapping> mappings) {
    public record Mapping(Long speakerId, TranscriptSpeakerSseResponse speaker) { }

    public static SpeakerMappingUpdatedSseEvent from(TranscriptSpeakerMappingUpdatedEvent event) {
        return new SpeakerMappingUpdatedSseEvent("SPEAKER_MAPPING_UPDATED", event.meetingId(),
                event.recordingSessionId(), event.mappings().stream()
                .map(m -> new Mapping(m.speakerId(),
                        new TranscriptSpeakerSseResponse(m.teamMemberId(), m.displayName()))).toList());
    }
}
