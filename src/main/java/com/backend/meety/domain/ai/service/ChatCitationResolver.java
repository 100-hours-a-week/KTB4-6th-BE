package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.ChatCitationSourceType;
import com.backend.meety.domain.ai.client.ChatAiCitation;
import com.backend.meety.domain.ai.dto.ChatCitation;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.repository.MeetingSummaryRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatCitationResolver {

    private final MeetingRepository meetingRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final MeetingSummaryRepository meetingSummaryRepository;
    private final ObjectMapper objectMapper;

    public String resolve(Long teamId, Long aiRequestId, List<ChatAiCitation> citations) {
        Map<Long, Optional<Meeting>> meetings = new HashMap<>();
        List<ChatCitation> resolved = Optional.ofNullable(citations).orElseGet(List::of).stream()
                .distinct()
                .map(citation -> toCitation(teamId, aiRequestId, citation, meetings))
                .flatMap(Optional::stream)
                .toList();
        return objectMapper.writeValueAsString(resolved);
    }

    private Optional<ChatCitation> toCitation(Long teamId, Long aiRequestId, ChatAiCitation citation,
                                              Map<Long, Optional<Meeting>> meetings) {
        if (!hasValidFormat(citation)) {
            return reject(aiRequestId, "형식이 올바르지 않음", citation);
        }
        Optional<Meeting> meeting = meetings.computeIfAbsent(
                citation.meetingId(), meetingRepository::findByIdAndDeletedAtIsNull);
        if (meeting.isEmpty()) {
            return reject(aiRequestId, "회의가 없음", citation);
        }
        if (!meeting.get().getTeam().getId().equals(teamId)) {
            return reject(aiRequestId, "다른 팀 회의", citation);
        }
        if (ChatCitationSourceType.TRANSCRIPT.equals(citation.sourceType())) {
            return toTranscriptCitation(aiRequestId, citation, meeting.get());
        }
        return toSummaryCitation(aiRequestId, citation, meeting.get());
    }

    private boolean hasValidFormat(ChatAiCitation citation) {
        if (citation.meetingId() == null) {
            return false;
        }
        if (ChatCitationSourceType.TRANSCRIPT.equals(citation.sourceType())) {
            return citation.segmentId() != null;
        }
        if (ChatCitationSourceType.SUMMARY.equals(citation.sourceType())) {
            return citation.summaryId() != null;
        }
        return false;
    }

    private Optional<ChatCitation> toTranscriptCitation(Long aiRequestId, ChatAiCitation citation, Meeting meeting) {
        return transcriptSegmentRepository.findById(citation.segmentId())
                .filter(segment -> segment.getDeletedAt() == null)
                .map(segment -> segment.getMeeting().getId().equals(meeting.getId())
                        ? Optional.of(ChatCitation.transcript(meeting, segment))
                        : reject(aiRequestId, "전사 문장이 다른 회의 소속", citation))
                .orElseGet(() -> reject(aiRequestId, "전사 문장이 없음", citation));
    }

    private Optional<ChatCitation> toSummaryCitation(Long aiRequestId, ChatAiCitation citation, Meeting meeting) {
        return meetingSummaryRepository.findById(citation.summaryId())
                .filter(summary -> summary.getDeletedAt() == null)
                .map(summary -> summary.getMeeting().getId().equals(meeting.getId())
                        ? Optional.of(ChatCitation.summary(meeting, summary))
                        : reject(aiRequestId, "요약이 다른 회의 소속", citation))
                .orElseGet(() -> reject(aiRequestId, "요약이 없음", citation));
    }

    private Optional<ChatCitation> reject(Long aiRequestId, String reason, ChatAiCitation citation) {
        log.warn("AI 답변 근거를 제외합니다. aiRequestId={}, reason={}, citation={}", aiRequestId, reason, citation);
        return Optional.empty();
    }
}
