package com.backend.meety.domain.meeting.dto;

import java.time.LocalDate;
import java.util.List;

public record MeetingGroupResponse(
        LocalDate date,
        int meetingCount,
        List<MeetingListItemResponse> meetings
) {

    public static MeetingGroupResponse of(LocalDate date, List<MeetingListItemResponse> meetings) {
        return new MeetingGroupResponse(date, meetings.size(), meetings);
    }
}
