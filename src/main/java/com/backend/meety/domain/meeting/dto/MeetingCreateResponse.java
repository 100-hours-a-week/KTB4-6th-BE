package com.backend.meety.domain.meeting.dto;

import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import java.time.LocalDateTime;
import java.util.List;

public record MeetingCreateResponse(
        Long meetingId,
        Long teamId,
        Long createdByTeamMemberId,
        String title,
        String purpose,
        String note,
        LocalDateTime scheduledAt,
        Integer targetDurationMinutes,
        MeetingStatus status,
        LocalDateTime createdAt,
        List<MeetingAttendeeResponse> attendees
) {

    public static MeetingCreateResponse from(Meeting meeting, List<MeetingParticipant> attendees) {
        return new MeetingCreateResponse(
                meeting.getId(),
                meeting.getTeam().getId(),
                meeting.getCreatedByTeamMember().getId(),
                meeting.getTitle(),
                meeting.getPurpose(),
                meeting.getNote(),
                meeting.getScheduledAt(),
                meeting.getTargetDurationMinutes(),
                meeting.getStatus(),
                meeting.getCreatedAt(),
                attendees.stream().map(MeetingAttendeeResponse::from).toList()
        );
    }
}
