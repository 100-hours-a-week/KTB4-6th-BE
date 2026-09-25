package com.backend.meety.domain.transcript.controller;

import com.backend.meety.domain.transcript.dto.TranscriptSegmentListResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingDetailResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingRequest;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerResponse;
import com.backend.meety.domain.transcript.service.TranscriptService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class TranscriptController {

    private final TranscriptService transcriptService;

    @GetMapping("/meetings/{meetingId}/transcripts")
    public ResponseEntity<ApiResponse<TranscriptSegmentListResponse>> getTranscripts(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId,
            @RequestParam(required = false) String keyword
    ) {
        return ResponseEntity.ok(ApiResponse.success(transcriptService.getTranscripts(userId, meetingId, keyword)));
    }

    @GetMapping("/meetings/{meetingId}/transcripts/{segmentId}/speaker-mapping")
    public ResponseEntity<ApiResponse<TranscriptSpeakerMappingDetailResponse>> getSpeakerMapping(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId,
            @PathVariable Long segmentId
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                transcriptService.getSpeakerMapping(userId, meetingId, segmentId)
        ));
    }

    @PutMapping("/meetings/{meetingId}/speakers/{transcriptSpeakerId}/mapping")
    public ResponseEntity<ApiResponse<TranscriptSpeakerResponse>> updateSpeakerMapping(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId,
            @PathVariable Long transcriptSpeakerId,
            @Valid @RequestBody TranscriptSpeakerMappingRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                transcriptService.updateSpeakerMapping(userId, meetingId, transcriptSpeakerId, request)
        ));
    }
}
