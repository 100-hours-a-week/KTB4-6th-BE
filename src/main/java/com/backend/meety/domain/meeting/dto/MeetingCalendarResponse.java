package com.backend.meety.domain.meeting.dto;

import java.util.List;

public record MeetingCalendarResponse(
        int year,
        int month,
        List<MeetingCalendarDateResponse> dates
) {
}
