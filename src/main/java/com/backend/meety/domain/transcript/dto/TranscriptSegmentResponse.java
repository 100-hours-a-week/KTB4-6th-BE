package com.backend.meety.domain.transcript.dto;

import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import java.time.LocalDateTime;

public record TranscriptSegmentResponse(
        Long segmentId,
        Long speakerId,
        String speakerLabel,
        String speakerAlias,
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
                speaker == null ? null : speaker.getId(),
                speaker == null ? null : speaker.getSpeakerLabel(),
                speaker == null ? null : speaker.getCustomAlias(),
                segment.getSequenceNumber(),
                segment.getContent(),
                segment.getStartedAtMs(),
                segment.getEndedAtMs(),
                segment.getRecognizedAt()
        );
    }
}
