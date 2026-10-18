package com.backend.meety.domain.credit.controller;

import com.backend.meety.domain.credit.dto.CreditLedgerListResponse;
import com.backend.meety.domain.credit.dto.TeamCreditResponse;
import com.backend.meety.domain.credit.service.TeamCreditService;
import com.backend.meety.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/teams")
@RequiredArgsConstructor
public class TeamCreditController {

    private final TeamCreditService teamCreditService;

    @GetMapping("/{teamId}/credits")
    public ResponseEntity<ApiResponse<TeamCreditResponse>> getBalance(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId
    ) {
        return ResponseEntity.ok(ApiResponse.success(teamCreditService.getBalance(userId, teamId)));
    }

    @GetMapping("/{teamId}/credit-ledgers")
    public ResponseEntity<ApiResponse<CreditLedgerListResponse>> getLedgers(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long teamId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer size
    ) {
        return ResponseEntity.ok(ApiResponse.success(teamCreditService.getLedgers(userId, teamId, cursor, size)));
    }
}
