package com.backend.meety.domain.transcript.dto;

import java.util.List;

public record TranscriptSpeakerListResponse(
        List<TranscriptSpeakerResponse> speakers
) {
}
