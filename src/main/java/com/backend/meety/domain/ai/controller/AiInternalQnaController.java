package com.backend.meety.domain.ai.controller;

import com.backend.meety.domain.ai.dto.InternalSummaryResponse;
import com.backend.meety.domain.ai.service.AiInternalQueryService;
import com.backend.meety.domain.meeting.dto.MeetingListResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentListResponse;
import com.backend.meety.global.response.ApiResponse;
import com.backend.meety.global.security.InternalApiHeader;
import java.time.LocalDate;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/qna")
public class AiInternalQnaController {

    private final AiInternalQueryService aiInternalQueryService;

    @GetMapping("/teams/{teamId}/meetings")
    public ResponseEntity<ApiResponse<MeetingListResponse>> getMeetings(
            @RequestHeader(InternalApiHeader.AI_REQUEST_ID) Long aiRequestId,
            @PathVariable Long teamId,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) String cursor
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                aiInternalQueryService.getMeetings(aiRequestId, keyword, from, to, cursor)));
    }

    @GetMapping("/meetings/{meetingId}/transcripts")
    public ResponseEntity<ApiResponse<TranscriptSegmentListResponse>> getTranscripts(
            @RequestHeader(InternalApiHeader.AI_REQUEST_ID) Long aiRequestId,
            @PathVariable Long meetingId,
            @RequestParam(required = false) String keyword
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                aiInternalQueryService.getTranscripts(aiRequestId, meetingId, keyword)));
    }

    @GetMapping("/meetings/{meetingId}/summaries")
    public ResponseEntity<ApiResponse<InternalSummaryResponse>> getLatestSummary(
            @RequestHeader(InternalApiHeader.AI_REQUEST_ID) Long aiRequestId,
            @PathVariable Long meetingId
    ) {
        return ResponseEntity.ok(ApiResponse.success(aiInternalQueryService.getLatestSummary(aiRequestId, meetingId)));
    }
}
