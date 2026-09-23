package com.backend.meety.domain.transcript.realtime;

public record TranscriptCreatedSseEvent(
        String type,
        Long meetingId,
        TranscriptSegmentSseResponse segment
) {

    private static final String TYPE = "TRANSCRIPT_CREATED";

    public static TranscriptCreatedSseEvent of(
            Long meetingId,
            Long transcriptSegmentId,
            Long sequenceNumber,
            String content,
            Long startedAtMs,
            Long endedAtMs,
            java.time.LocalDateTime recognizedAt
    ) {
        return new TranscriptCreatedSseEvent(
                TYPE,
                meetingId,
                new TranscriptSegmentSseResponse(
                        transcriptSegmentId,
                        sequenceNumber,
                        content,
                        startedAtMs,
                        endedAtMs,
                        recognizedAt,
                        null
                )
        );
    }
}
