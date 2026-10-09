package com.backend.meety.domain.ai.service;

import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.ChatAiHistoryMessage;
import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.client.ChatAiTranscriptSegment;
import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.ChatInputType;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ChatAiRequestFactoryTest {

    private final AiChatbotMessageRepository messages = mock(AiChatbotMessageRepository.class);
    private final TranscriptSegmentRepository transcripts = mock(TranscriptSegmentRepository.class);
    private final ChatAiRequestFactory factory = new ChatAiRequestFactory(messages, transcripts);

    private final Team team = team();
    private final TeamMember member = member(team);
    private final Meeting meeting = meeting(team, member);

    @Test
    @DisplayName("이전 질문은 답변이 있으면 user·assistant, 없으면 user만 넣고 이번 회의 전사를 전부 담는다")
    void buildsRequestWithHistoryAndTranscripts() {
        AiChatbotMessage answered = message(8800L, "배포 일정은요?");
        ReflectionTestUtils.setField(answered, "answer", "금요일입니다.");
        AiChatbotMessage failed = message(8801L, "담당자는요?");
        AiChatbotMessage current = message(8802L, "지금까지 결정된 사항 정리해줘");
        TranscriptSegment segment = withId(TranscriptSegment.createFinal(
                meeting, "seg-1", 8L, "배포는 금요일에 진행합시다.", 12000L, 15000L, NOW), 801L);
        when(messages.findPreviousByMeetingId(100L, 8802L)).thenReturn(List.of(answered, failed));
        when(transcripts.findAllByMeetingIdOrderBySequence(100L)).thenReturn(List.of(segment));

        ChatAiRequest request = factory.create(current);

        assertThat(request.aiRequestId()).isEqualTo(8802L);
        assertThat(request.meetingId()).isEqualTo(100L);
        assertThat(request.teamId()).isEqualTo(2L);
        assertThat(request.question()).isEqualTo("지금까지 결정된 사항 정리해줘");
        assertThat(request.conversationHistory()).containsExactly(
                ChatAiHistoryMessage.user("배포 일정은요?"),
                ChatAiHistoryMessage.assistant("금요일입니다."),
                ChatAiHistoryMessage.user("담당자는요?"));
        assertThat(request.transcriptSegments()).containsExactly(
                new ChatAiTranscriptSegment(801L, null, 8L, "배포는 금요일에 진행합시다.", 12000L, 15000L));
    }

    private AiChatbotMessage message(long id, String question) {
        AiRequest request = withId(AiRequest.create(team, member, "key-" + id, AiRequestType.CHAT), id);
        return withId(AiChatbotMessage.create(request, meeting, member, ChatInputType.TEXT, question), id);
    }
}
