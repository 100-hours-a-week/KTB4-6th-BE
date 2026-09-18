package com.backend.meety.domain.team.dto;

import com.backend.meety.domain.team.entity.TeamBlock;
import java.time.LocalDateTime;

public record TeamBlockItemResponse(
        Long blockId,
        Long userId,
        String displayName,
        LocalDateTime createdAt
) {

    public static TeamBlockItemResponse of(TeamBlock teamBlock, String displayName) {
        return new TeamBlockItemResponse(
                teamBlock.getId(),
                teamBlock.getUser().getId(),
                displayName,
                teamBlock.getCreatedAt()
        );
    }
}
