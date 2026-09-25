package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.client.SummaryAiRequest;
import com.backend.meety.domain.ai.client.SummaryAiSegment;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * AI 실시간 전사에 화자 정보가 없어 v1은 화자 없이 조립한다 (AI팀 합의).
 * 화자가 도입되면 이 클래스와 전사 저장부만 수정한다.
 */
@Component
@RequiredArgsConstructor
public class SummaryAiRequestFactory {

    private static final long UNKNOWN_SPEAKER_ID = 0L;

    private final TranscriptSegmentRepository transcriptSegmentRepository;

    public SummaryAiRequest create(AiRequest aiRequest, Meeting meeting) {
        List<SummaryAiSegment> segments = transcriptSegmentRepository
                .findAllByMeetingIdOrderBySequence(meeting.getId()).stream()
                .map(this::toSegment)
                .toList();
        return new SummaryAiRequest(
                String.valueOf(aiRequest.getId()),
                meeting.getId(),
                meeting.getTitle(),
                emptyIfNull(meeting.getPurpose()),
                emptyIfNull(meeting.getNote()),
                meeting.getStartedAt().atZone(ZoneId.systemDefault()).toOffsetDateTime().toString(),
                List.of(),
                segments
        );
    }

    private SummaryAiSegment toSegment(TranscriptSegment segment) {
        Long endedAtMs = segment.getEndedAtMs() != null ? segment.getEndedAtMs() : segment.getStartedAtMs();
        return new SummaryAiSegment(
                segment.getId(),
                UNKNOWN_SPEAKER_ID,
                segment.getSequenceNumber(),
                segment.getContent(),
                segment.getStartedAtMs(),
                endedAtMs
        );
    }

    private String emptyIfNull(String value) {
        return value != null ? value : "";
    }
}
