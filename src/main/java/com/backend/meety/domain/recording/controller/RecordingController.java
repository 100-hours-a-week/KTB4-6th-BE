package com.backend.meety.domain.recording.controller;

import com.backend.meety.domain.recording.dto.RecordingSessionResponse;
import com.backend.meety.domain.recording.dto.RecordingStatusUpdateRequest;
import com.backend.meety.domain.recording.service.RecordingService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class RecordingController {

    private final RecordingService recordingService;

    @PostMapping("/meetings/{meetingId}/recordings")
    public ResponseEntity<ApiResponse<RecordingSessionResponse>> start(
            @AuthenticationPrincipal Long userId, @PathVariable Long meetingId
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(recordingService.start(userId, meetingId)));
    }

    @GetMapping("/meetings/{meetingId}/recording")
    public ResponseEntity<ApiResponse<RecordingSessionResponse>> getActive(
            @AuthenticationPrincipal Long userId, @PathVariable Long meetingId
    ) {
        return ResponseEntity.ok(ApiResponse.success(recordingService.getActive(userId, meetingId)));
    }

    @PatchMapping("/recordings/{recordingSessionId}")
    public ResponseEntity<ApiResponse<RecordingSessionResponse>> updateStatus(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long recordingSessionId,
            @Valid @RequestBody RecordingStatusUpdateRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(
                recordingService.updateStatus(userId, recordingSessionId, request.toStatus())));
    }
}
