package com.backend.meety.domain.ai.dto;

import com.backend.meety.domain.ai.entity.ChatInputType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChatCreateRequest(
        @NotBlank
        @Size(max = 1000)
        String question,

        @NotNull
        @Pattern(regexp = "TEXT|VOICE")
        String inputType
) {

    public ChatInputType toInputType() {
        return ChatInputType.valueOf(inputType);
    }
}
