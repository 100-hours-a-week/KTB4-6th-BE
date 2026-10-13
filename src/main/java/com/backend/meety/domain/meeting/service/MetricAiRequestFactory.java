package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.client.MetricAiRequest;
import com.backend.meety.domain.ai.client.MetricAiSegment;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingSummary;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MetricAiRequestFactory {

    private static final long UNKNOWN_SPEAKER_ID = 0L;

    private final TranscriptSegmentRepository transcriptSegmentRepository;

    public MetricAiRequest create(AiRequest aiRequest, MeetingSummary summary) {
        Meeting meeting = summary.getMeeting();
        List<MetricAiSegment> segments = transcriptSegmentRepository
                .findAllByMeetingIdOrderBySequence(meeting.getId()).stream()
                .map(this::toSegment)
                .toList();
        return new MetricAiRequest(
                String.valueOf(aiRequest.getId()),
                meeting.getId(),
                summary.getContent(),
                segments
        );
    }

    private MetricAiSegment toSegment(TranscriptSegment segment) {
        Long endedAtMs = segment.getEndedAtMs() != null ? segment.getEndedAtMs() : segment.getStartedAtMs();
        return new MetricAiSegment(
                segment.getId(),
                resolveSpeakerId(segment.getTranscriptSpeaker()),
                segment.getSequenceNumber(),
                segment.getContent(),
                segment.getStartedAtMs(),
                endedAtMs
        );
    }

    private Long resolveSpeakerId(TranscriptSpeaker speaker) {
        if (speaker == null || speaker.getId() == null) {
            return UNKNOWN_SPEAKER_ID;
        }
        return speaker.getId();
    }
}
