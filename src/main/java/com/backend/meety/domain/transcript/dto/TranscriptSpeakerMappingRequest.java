package com.backend.meety.domain.transcript.dto;

import jakarta.validation.constraints.Size;

public record TranscriptSpeakerMappingRequest(
        Long teamMemberId,

        @Size(max = 10, message = "발화자 별칭을 확인해주세요.")
        String customAlias
) {
}
