package com.backend.meety.domain.meeting.controller;

import com.backend.meety.domain.meeting.dto.SummaryCreateResponse;
import com.backend.meety.domain.meeting.service.MeetingSummaryService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class MeetingSummaryController {

    private final MeetingSummaryService meetingSummaryService;

    @PostMapping("/meetings/{meetingId}/summaries")
    public ResponseEntity<ApiResponse<SummaryCreateResponse>> requestSummary(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId,
            @RequestHeader("Idempotency-Key")
            @NotBlank(message = "Idempotency-Key 헤더를 확인해주세요.")
            @Size(max = 100, message = "Idempotency-Key 헤더를 확인해주세요.")
            String idempotencyKey
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(meetingSummaryService.requestSummary(userId, meetingId, idempotencyKey)));
    }
}
