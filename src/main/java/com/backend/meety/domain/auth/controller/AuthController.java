package com.backend.meety.domain.auth.controller;

import com.backend.meety.domain.auth.dto.LoginRequest;
import com.backend.meety.domain.auth.dto.LoginResponse;
import com.backend.meety.domain.auth.dto.LogoutRequest;
import com.backend.meety.domain.auth.dto.TokenRefreshRequest;
import com.backend.meety.domain.auth.dto.TokenRefreshResult;
import com.backend.meety.domain.auth.service.AuthService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/{provider}/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(@PathVariable String provider,
                                                            @Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.login(provider, request.authorizationCode())));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@Valid @RequestBody LogoutRequest request) {
        authService.logout(request.accessToken(), request.refreshToken());
        return ResponseEntity.ok(ApiResponse.success());
    }

    @PostMapping("/token/refresh")
    public ResponseEntity<ApiResponse<TokenRefreshResult>> refresh(@Valid @RequestBody TokenRefreshRequest request) {
        return ResponseEntity.ok(ApiResponse.success(authService.refresh(request.refreshToken())));
    }
}
