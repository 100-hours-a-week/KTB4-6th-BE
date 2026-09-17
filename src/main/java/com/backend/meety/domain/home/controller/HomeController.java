package com.backend.meety.domain.home.controller;

import com.backend.meety.domain.home.dto.HomeResponse;
import com.backend.meety.domain.home.service.HomeService;
import com.backend.meety.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class HomeController {

    private final HomeService homeService;

    @GetMapping("/home")
    public ResponseEntity<ApiResponse<HomeResponse>> getHome(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.success(homeService.getHome(userId)));
    }
}