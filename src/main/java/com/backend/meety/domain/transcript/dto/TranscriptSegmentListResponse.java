package com.backend.meety.domain.transcript.dto;

import java.util.List;

public record TranscriptSegmentListResponse(
        List<TranscriptSegmentResponse> segments
) {
}
