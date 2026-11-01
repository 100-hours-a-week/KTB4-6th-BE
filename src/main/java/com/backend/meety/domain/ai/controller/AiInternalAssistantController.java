package com.backend.meety.domain.ai.controller;

import com.backend.meety.domain.ai.dto.InternalTeamMembersResponse;
import com.backend.meety.domain.ai.service.AiInternalQueryService;
import com.backend.meety.global.response.ApiResponse;
import com.backend.meety.global.security.InternalApiHeader;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/internal/v1/assistant")
public class AiInternalAssistantController {

    private final AiInternalQueryService aiInternalQueryService;

    @GetMapping("/team-members")
    public ResponseEntity<ApiResponse<InternalTeamMembersResponse>> getTeamMembers(
            @RequestHeader(name = InternalApiHeader.AI_REQUEST_ID, required = false) Long aiRequestId
    ) {
        return ResponseEntity.ok(ApiResponse.success(aiInternalQueryService.getActiveTeamMembers(aiRequestId)));
    }
}
