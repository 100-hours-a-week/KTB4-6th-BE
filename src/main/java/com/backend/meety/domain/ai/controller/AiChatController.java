package com.backend.meety.domain.ai.controller;

import com.backend.meety.domain.ai.dto.ChatCreateRequest;
import com.backend.meety.domain.ai.dto.ChatCreateResponse;
import com.backend.meety.domain.ai.service.AiChatService;
import com.backend.meety.global.response.ApiResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1")
public class AiChatController {

    private final AiChatService aiChatService;

    @PostMapping("/meetings/{meetingId}/chats")
    public ResponseEntity<ApiResponse<ChatCreateResponse>> requestChat(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long meetingId,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody ChatCreateRequest request
    ) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(ApiResponse.success(aiChatService.requestChat(
                        userId, meetingId, idempotencyKey, request.toInputType(), request.question())));
    }
}
