package com.backend.meety.domain.team.controller;

import com.backend.meety.domain.team.dto.InvitationCodeResponse;
import com.backend.meety.domain.team.dto.MyTeamResponse;
import com.backend.meety.domain.team.dto.LeaderDelegateRequest;
import com.backend.meety.domain.team.dto.TeamBlockListResponse;
import com.backend.meety.domain.team.dto.TeamCreateRequest;
import com.backend.meety.domain.team.dto.TeamCreateResponse;
import com.backend.meety.domain.team.dto.TeamDetailResponse;
import com.backend.meety.domain.team.dto.TeamMemberListResponse;
import com.backend.meety.domain.team.service.TeamMemberService;
import com.backend.meety.domain.team.service.TeamService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamController {

    private final TeamService teamService;
    private final TeamMemberService teamMemberService;

    @PostMapping
    public ResponseEntity<ApiResponse<TeamCreateResponse>> create(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody TeamCreateRequest request
    ) {
        TeamCreateResponse response = teamService.create(userId, request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponse<MyTeamResponse>> getMyTeam(@AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(ApiResponse.success(teamService.getMyTeam(userId)));
    }

    @GetMapping("/{teamId}")
    public ResponseEntity<ApiResponse<TeamDetailResponse>> getTeam(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId
    ) {
        return ResponseEntity.ok(ApiResponse.success(teamService.getTeam(userId, teamId)));
    }

    @GetMapping("/{teamId}/members")
    public ResponseEntity<ApiResponse<TeamMemberListResponse>> getMembers(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId
    ) {
        return ResponseEntity.ok(ApiResponse.success(teamMemberService.getMembers(userId, teamId)));
    }

    @DeleteMapping("/{teamId}/members/me")
    public ResponseEntity<Void> leave(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId
    ) {
        teamMemberService.leave(userId, teamId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{teamId}/members/{teamMemberId}")
    public ResponseEntity<Void> kick(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId,
            @PathVariable Long teamMemberId
    ) {
        teamMemberService.kick(userId, teamId, teamMemberId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{teamId}/leader")
    public ResponseEntity<Void> delegateLeader(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId,
            @Valid @RequestBody LeaderDelegateRequest request
    ) {
        teamMemberService.delegateLeader(userId, teamId, request.teamMemberId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/{teamId}/blocks")
    public ResponseEntity<ApiResponse<TeamBlockListResponse>> getBlocks(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId
    ) {
        return ResponseEntity.ok(ApiResponse.success(teamMemberService.getBlocks(userId, teamId)));
    }

    @DeleteMapping("/{teamId}/blocks/{blockId}")
    public ResponseEntity<Void> unblock(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId,
            @PathVariable Long blockId
    ) {
        teamMemberService.unblock(userId, teamId, blockId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{teamId}/invitation-codes")
    public ResponseEntity<ApiResponse<InvitationCodeResponse>> regenerateInvitationCode(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId
    ) {
        InvitationCodeResponse response = teamService.regenerateInvitationCode(userId, teamId);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(response));
    }
}
