package com.backend.meety.domain.auth.controller;

import com.backend.meety.domain.auth.dto.LoginRequest;
import com.backend.meety.domain.auth.dto.LoginResponse;
import com.backend.meety.domain.auth.service.AuthService;
import com.backend.meety.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
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
    public ApiResponse<LoginResponse> login(@PathVariable("provider") String provider,
                                            @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(provider, request.authorizationCode()));
    }
}
