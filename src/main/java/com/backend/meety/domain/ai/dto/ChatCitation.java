package com.backend.meety.domain.ai.dto;

import com.backend.meety.domain.ai.ChatCitationSourceType;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ChatCitation(
        String sourceType,
        Long meetingId,
        Long segmentId,
        Long summaryId,
        String meetingTitle,
        LocalDateTime meetingStartedAt,
        Long startedAtMs
) {

    public static ChatCitation transcript(Meeting meeting, TranscriptSegment segment) {
        return new ChatCitation(ChatCitationSourceType.TRANSCRIPT, meeting.getId(), segment.getId(), null,
                meeting.getTitle(), meeting.getStartedAt(), segment.getStartedAtMs());
    }

    public static ChatCitation summary(Meeting meeting, MeetingSummary summary) {
        return new ChatCitation(ChatCitationSourceType.SUMMARY, meeting.getId(), null, summary.getId(),
                meeting.getTitle(), meeting.getStartedAt(), null);
    }
}
