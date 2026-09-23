package com.backend.meety.domain.recording.controller;

import com.backend.meety.domain.recording.dto.AudioFileUploadUrlRequest;
import com.backend.meety.domain.recording.dto.AudioFileUploadUrlResponse;
import com.backend.meety.domain.recording.service.AudioFileService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class AudioFileController {

    private final AudioFileService audioFileService;

    @PostMapping("/recordings/{recordingSessionId}/audio-files")
    public ResponseEntity<ApiResponse<AudioFileUploadUrlResponse>> createUploadUrl(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long recordingSessionId,
            @Valid @RequestBody AudioFileUploadUrlRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(
                        audioFileService.createUploadUrl(userId, recordingSessionId, request.contentType())));
    }
}
