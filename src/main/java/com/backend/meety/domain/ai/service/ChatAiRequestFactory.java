package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.client.ChatAiHistoryMessage;
import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.client.ChatAiTranscriptSegment;
import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.util.List;
import java.util.stream.Stream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ChatAiRequestFactory {

    private final AiChatbotMessageRepository chatbotMessageRepository;
    private final TranscriptSegmentRepository transcriptSegmentRepository;

    public ChatAiRequest create(AiChatbotMessage message) {
        Long meetingId = message.getMeeting().getId();
        return new ChatAiRequest(
                message.getAiRequest().getId(),
                meetingId,
                message.getAiRequest().getTeam().getId(),
                message.getQuestion(),
                conversationHistory(meetingId, message.getId()),
                transcriptSegments(meetingId)
        );
    }

    private List<ChatAiHistoryMessage> conversationHistory(Long meetingId, Long messageId) {
        return chatbotMessageRepository.findPreviousByMeetingId(meetingId, messageId).stream()
                .flatMap(this::toHistory)
                .toList();
    }

    private Stream<ChatAiHistoryMessage> toHistory(AiChatbotMessage previous) {
        ChatAiHistoryMessage question = ChatAiHistoryMessage.user(previous.getQuestion());
        if (previous.getAnswer() == null) {
            return Stream.of(question);
        }
        return Stream.of(question, ChatAiHistoryMessage.assistant(previous.getAnswer()));
    }

    private List<ChatAiTranscriptSegment> transcriptSegments(Long meetingId) {
        return transcriptSegmentRepository.findAllByMeetingIdOrderBySequence(meetingId).stream()
                .map(TranscriptSegmentResponse::from)
                .map(segment -> new ChatAiTranscriptSegment(
                        segment.segmentId(),
                        segment.speakerDisplayName(),
                        segment.sequenceNumber(),
                        segment.content(),
                        segment.startedAtMs(),
                        segment.endedAtMs()))
                .toList();
    }
}
