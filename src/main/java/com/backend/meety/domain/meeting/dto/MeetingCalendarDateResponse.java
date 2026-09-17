package com.backend.meety.domain.meeting.dto;

import java.time.LocalDate;
import java.util.List;

public record MeetingCalendarDateResponse(
        LocalDate date,
        List<MeetingCalendarItemResponse> meetings
) {
}
