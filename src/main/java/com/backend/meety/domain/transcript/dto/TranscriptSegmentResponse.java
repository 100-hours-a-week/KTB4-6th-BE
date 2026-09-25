package com.backend.meety.domain.transcript.dto;

import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import java.time.LocalDateTime;

public record TranscriptSegmentResponse(
        Long segmentId,
        String speakerDisplayName,
        Long sequenceNumber,
        String content,
        Long startedAtMs,
        Long endedAtMs,
        LocalDateTime recognizedAt
) {

    public static TranscriptSegmentResponse from(TranscriptSegment segment) {
        TranscriptSpeaker speaker = segment.getTranscriptSpeaker();
        return new TranscriptSegmentResponse(
                segment.getId(),
                resolveSpeakerDisplayName(speaker),
                segment.getSequenceNumber(),
                segment.getContent(),
                segment.getStartedAtMs(),
                segment.getEndedAtMs(),
                segment.getRecognizedAt()
        );
    }

    private static String resolveSpeakerDisplayName(TranscriptSpeaker speaker) {
        if (speaker == null) {
            return null;
        }
        if (speaker.getCustomAlias() != null) {
            return speaker.getCustomAlias();
        }
        if (speaker.getMappedTeamMember() != null) {
            return speaker.getMappedTeamMember().getDisplayName();
        }
        return speaker.getSpeakerLabel();
    }
}
