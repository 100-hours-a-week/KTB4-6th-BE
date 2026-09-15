package com.backend.meety.domain.meeting.dto;

import java.util.List;

public record MeetingListResponse(
        List<MeetingGroupResponse> groups,
        String nextCursor,
        boolean hasNext
) {
}
