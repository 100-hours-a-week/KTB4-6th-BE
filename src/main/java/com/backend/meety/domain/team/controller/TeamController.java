package com.backend.meety.domain.team.controller;

import com.backend.meety.domain.team.dto.TeamCreateRequest;
import com.backend.meety.domain.team.dto.TeamCreateResponse;
import com.backend.meety.domain.team.service.TeamService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;

    @PostMapping
    public ResponseEntity<ApiResponse<TeamCreateResponse>> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody TeamCreateRequest request
    ) {
        TeamCreateResponse response = teamService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

}
