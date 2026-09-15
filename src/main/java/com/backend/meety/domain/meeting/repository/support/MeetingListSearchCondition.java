package com.backend.meety.domain.meeting.repository.support;

import java.time.LocalDateTime;

public record MeetingListSearchCondition(
        Long teamId,
        String keyword,
        LocalDateTime fromInclusive,
        LocalDateTime toExclusive,
        MeetingCursor cursor,
        int limit
) {
}
