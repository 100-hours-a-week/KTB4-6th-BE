package com.backend.meety.domain.meeting.realtime;

import com.backend.meety.domain.recording.entity.RecordingSessionStatus;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MeetingRealtimeEvent(
        MeetingEventType type,
        Long meetingId,
        Long recordingSessionId,
        RecordingSessionStatus status,
        Long transcriptSegmentId,
        Long sequenceNumber,
        String text,
        Long startedAtMs,
        Long endedAtMs,
        LocalDateTime recognizedAt
) {

    public static MeetingRealtimeEvent recording(
            MeetingEventType type,
            Long meetingId,
            Long recordingSessionId,
            RecordingSessionStatus status
    ) {
        return new MeetingRealtimeEvent(type, meetingId, recordingSessionId, status,
                null, null, null, null, null, null);
    }

    public static MeetingRealtimeEvent transcript(
            Long meetingId,
            Long recordingSessionId,
            Long transcriptSegmentId,
            Long sequenceNumber,
            String text,
            Long startedAtMs,
            Long endedAtMs,
            LocalDateTime recognizedAt
    ) {
        return new MeetingRealtimeEvent(MeetingEventType.TRANSCRIPT_CREATED, meetingId, recordingSessionId, null,
                transcriptSegmentId, sequenceNumber, text, startedAtMs, endedAtMs, recognizedAt);
    }
}
