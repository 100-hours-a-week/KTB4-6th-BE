package com.backend.meety.domain.recording.controller;

import com.backend.meety.domain.recording.dto.AudioFileDeleteResponse;
import com.backend.meety.domain.recording.dto.AudioFileDetailResponse;
import com.backend.meety.domain.recording.dto.AudioFileDownloadUrlResponse;
import com.backend.meety.domain.recording.dto.AudioFileResponse;
import com.backend.meety.domain.recording.dto.AudioFileUploadCompleteRequest;
import com.backend.meety.domain.recording.dto.AudioFileUploadUrlRequest;
import com.backend.meety.domain.recording.dto.AudioFileUploadUrlResponse;
import com.backend.meety.domain.recording.service.AudioFileService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
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

    @GetMapping("/meetings/{meetingId}/audio-file")
    public ResponseEntity<ApiResponse<AudioFileDetailResponse>> getByMeeting(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId
    ) {
        return ResponseEntity.ok(ApiResponse.success(audioFileService.getByMeeting(userId, meetingId)));
    }

    @GetMapping("/audio-files/{audioFileId}/download-url")
    public ResponseEntity<ApiResponse<AudioFileDownloadUrlResponse>> createDownloadUrl(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long audioFileId
    ) {
        return ResponseEntity.ok(ApiResponse.success(audioFileService.createDownloadUrl(userId, audioFileId)));
    }

    @DeleteMapping("/audio-files/{audioFileId}")
    public ResponseEntity<ApiResponse<AudioFileDeleteResponse>> requestDelete(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long audioFileId
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(audioFileService.requestDelete(userId, audioFileId)));
    }

    @PatchMapping("/audio-files/{audioFileId}")
    public ResponseEntity<ApiResponse<AudioFileResponse>> completeUpload(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long audioFileId,
            @Valid @RequestBody AudioFileUploadCompleteRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.success(audioFileService.completeUpload(
                userId, audioFileId, request.fileSizeBytes(), request.durationMs())));
    }
}
