package com.backend.meety.domain.user.controller;

import com.backend.meety.domain.user.dto.UserVoiceConsentResponse;
import com.backend.meety.domain.user.dto.UserVoiceProfileResponse;
import com.backend.meety.domain.user.dto.UserVoiceProfileUploadUrlResponse;
import com.backend.meety.domain.user.dto.VoiceProfileUploadUrlRequest;
import com.backend.meety.domain.user.service.UserService;
import com.backend.meety.domain.user.service.UserVoiceProfileService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;
    private final UserVoiceProfileService userVoiceProfileService;

    @DeleteMapping("/me")
    public ResponseEntity<Void> withdraw(
            @AuthenticationPrincipal Long userId,
            @CookieValue(name = "${auth.access-token-cookie-name}", required = false) String accessToken
    ) {
        userService.withdraw(userId, accessToken);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/me/voice-profile/consent")
    public ResponseEntity<ApiResponse<UserVoiceConsentResponse>> consentToVoiceCollection(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.success(userVoiceProfileService.consent(userId)));
    }

    @PostMapping("/me/voice-profile/upload-url")
    public ResponseEntity<ApiResponse<UserVoiceProfileUploadUrlResponse>> createVoiceProfileUploadUrl(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody VoiceProfileUploadUrlRequest request
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(userVoiceProfileService.createUploadUrl(userId, request.contentType())));
    }

    @PostMapping("/me/voice-profile")
    public ResponseEntity<ApiResponse<UserVoiceProfileResponse>> registerVoiceProfile(
            @AuthenticationPrincipal Long userId
    ) {
        return ResponseEntity.ok(ApiResponse.success(userVoiceProfileService.register(userId)));
    }
}
