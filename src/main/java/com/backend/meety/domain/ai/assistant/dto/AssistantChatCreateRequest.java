package com.backend.meety.domain.ai.assistant.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record AssistantChatCreateRequest(
        @NotNull Long teamId,
        @NotBlank @Size(max = 1000) String question
) {
}
