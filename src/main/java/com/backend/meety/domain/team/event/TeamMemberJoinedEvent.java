package com.backend.meety.domain.team.event;

import java.util.UUID;

public record TeamMemberJoinedEvent(
        String eventId,
        Long teamId,
        Long joinedTeamMemberId,
        Long joinedUserId,
        String joinedDisplayName,
        TeamMemberJoinType joinType
) {

    public static TeamMemberJoinedEvent create(
            Long teamId,
            Long joinedTeamMemberId,
            Long joinedUserId,
            String joinedDisplayName,
            TeamMemberJoinType joinType
    ) {
        return new TeamMemberJoinedEvent(
                UUID.randomUUID().toString(),
                teamId,
                joinedTeamMemberId,
                joinedUserId,
                joinedDisplayName,
                joinType
        );
    }
}
