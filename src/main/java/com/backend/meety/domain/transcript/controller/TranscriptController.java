package com.backend.meety.domain.transcript.controller;

import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.service.TranscriptService;
import com.backend.meety.global.response.ApiResponse;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class TranscriptController {

    private final TranscriptService transcriptService;

    @GetMapping("/meetings/{meetingId}/transcripts")
    public ResponseEntity<ApiResponse<List<TranscriptSegmentResponse>>> getTranscripts(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId,
            @RequestParam(required = false) String keyword
    ) {
        return ResponseEntity.ok(ApiResponse.success(transcriptService.getTranscripts(userId, meetingId, keyword)));
    }
}
