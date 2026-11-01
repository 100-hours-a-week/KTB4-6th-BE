package com.backend.meety.domain.ai.dto;

import java.util.List;

public record InternalTeamMembersResponse(List<Member> members) {
    public record Member(Long teamMemberId, String displayName) {
    }
}
