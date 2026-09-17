package com.backend.meety.domain.meeting.repository.support;

import java.time.LocalDate;
import java.time.LocalDateTime;

public record MeetingListSearchCondition(
        Long teamId,
        String keyword,
        LocalDateTime fromInclusive,
        LocalDateTime toExclusive,
        LocalDate cursorDate,
        int limit
) {
}
