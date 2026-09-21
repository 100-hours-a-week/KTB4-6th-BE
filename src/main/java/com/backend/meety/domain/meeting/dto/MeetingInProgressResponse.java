package com.backend.meety.domain.meeting.dto;

public record MeetingInProgressResponse(
        boolean hasInProgressMeeting
) {
    public static MeetingInProgressResponse from(boolean hasInProgressMeeting) {
        return new MeetingInProgressResponse(hasInProgressMeeting);
    }
}
