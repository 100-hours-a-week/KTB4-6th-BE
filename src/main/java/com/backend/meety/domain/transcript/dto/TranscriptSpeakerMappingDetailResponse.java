package com.backend.meety.domain.transcript.dto;

import java.util.List;

public record TranscriptSpeakerMappingDetailResponse(
        TranscriptSpeakerMappingStateResponse speaker,
        List<TranscriptSpeakerMappingParticipantResponse> participants
) {
}
