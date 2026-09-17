package com.backend.meety.domain.meeting.dto;

import java.util.List;

public record MeetingParticipantListResponse(
        int participantsCount,
        List<MeetingParticipantResponse> participants
) {
}
