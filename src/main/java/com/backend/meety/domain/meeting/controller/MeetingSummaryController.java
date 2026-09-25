package com.backend.meety.domain.meeting.controller;

import com.backend.meety.domain.meeting.dto.SummaryCreateResponse;
import com.backend.meety.domain.meeting.dto.SummaryDetailResponse;
import com.backend.meety.domain.meeting.service.MeetingSummaryService;
import com.backend.meety.global.response.ApiResponse;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MeetingSummaryController {

    private final MeetingSummaryService meetingSummaryService;

    @GetMapping("/meetings/{meetingId}/summaries")
    public ResponseEntity<ApiResponse<SummaryDetailResponse>> getLatestSummary(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId
    ) {
        return ResponseEntity.ok(ApiResponse.success(meetingSummaryService.getLatestSummary(userId, meetingId)));
    }

    @PostMapping("/meetings/{meetingId}/summaries")
    public ResponseEntity<ApiResponse<SummaryCreateResponse>> requestSummary(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(
                        meetingSummaryService.requestSummary(userId, meetingId, resolveIdempotencyKey(idempotencyKey))));
    }

    // 프론트 v2에서 멱등키 헤더를 보내기 전까지는 서버가 임의 UUID로 대신 채운다 (요청별 멱등성 없음)
    private String resolveIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return idempotencyKey;
    }
}
