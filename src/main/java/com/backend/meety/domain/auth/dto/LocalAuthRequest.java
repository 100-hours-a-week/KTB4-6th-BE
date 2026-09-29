package com.backend.meety.domain.auth.dto;

import jakarta.validation.constraints.NotBlank;

public record LocalAuthRequest(
        @NotBlank(message = "아이디를 입력해주세요.")
        String loginId,

        @NotBlank(message = "비밀번호를 입력해주세요.")
        String password
) {
}
