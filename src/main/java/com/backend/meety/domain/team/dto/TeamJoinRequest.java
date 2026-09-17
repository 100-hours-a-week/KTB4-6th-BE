package com.backend.meety.domain.team.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record TeamJoinRequest(
        @NotBlank(message = "초대 코드는 8자리여야 합니다.")
        @Pattern(regexp = "^[A-Za-z0-9]{8}$", message = "초대 코드는 8자리여야 합니다.")
        String invitationCode,

        @NotBlank(message = "사용자 이름을 확인해주세요.")
        @Pattern(regexp = "^[가-힣a-zA-Z0-9]{2,10}$", message = "사용자 이름을 확인해주세요.")
        String displayName
) {
}
