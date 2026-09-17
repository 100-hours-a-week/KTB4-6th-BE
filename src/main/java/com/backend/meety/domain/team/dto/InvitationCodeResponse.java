package com.backend.meety.domain.team.dto;

import com.backend.meety.domain.team.entity.TeamInvitationCode;
import java.time.LocalDateTime;

public record InvitationCodeResponse(
        Long invitationCodeId,
        String code,
        LocalDateTime createdAt
) {

    public static InvitationCodeResponse from(TeamInvitationCode invitationCode) {
        return new InvitationCodeResponse(
                invitationCode.getId(),
                invitationCode.getCode(),
                invitationCode.getCreatedAt()
        );
    }
}
