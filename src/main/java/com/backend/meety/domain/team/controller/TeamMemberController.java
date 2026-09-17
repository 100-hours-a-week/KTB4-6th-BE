package com.backend.meety.domain.team.controller;

import com.backend.meety.domain.team.dto.TeamJoinRequest;
import com.backend.meety.domain.team.dto.TeamJoinResponse;
import com.backend.meety.domain.team.service.TeamMemberService;
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
@RequestMapping("/api/v1/team-memberships")
@RequiredArgsConstructor
public class TeamMemberController {

    private final TeamMemberService teamMemberService;

    @PostMapping
    public ResponseEntity<ApiResponse<TeamJoinResponse>> join(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody TeamJoinRequest request
    ) {
        TeamJoinResponse response = teamMemberService.join(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }
}


