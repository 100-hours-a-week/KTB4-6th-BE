package com.backend.meety.domain.ai.service;

import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.ChatInputType;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ChatProcessingServiceTest {

    private final AiChatbotMessageRepository messages = mock(AiChatbotMessageRepository.class);
    private final ChatAiRequestFactory factory = mock(ChatAiRequestFactory.class);
    private final ChatProcessingService service = new ChatProcessingService(messages, factory);

    private final Team team = team();
    private final TeamMember member = member(team);
    private final AiRequest aiRequest = withId(AiRequest.create(team, member, "key", AiRequestType.CHAT), 900L);
    private final AiChatbotMessage message = withId(
            AiChatbotMessage.create(aiRequest, meeting(team, member), member, ChatInputType.TEXT, "질문"), 8801L);

    @Test
    @DisplayName("접수 상태 질문은 PROCESSING으로 바꾸고 AI 요청을 만든다")
    void startsAcceptedQuestion() {
        ChatAiRequest request = new ChatAiRequest(900L, 100L, 2L, "질문", List.of(), List.of());
        when(messages.findWithRequestByAiRequestId(900L)).thenReturn(Optional.of(message));
        when(factory.create(message)).thenReturn(request);

        Optional<ChatAiRequest> result = service.startProcessing(900L);

        assertThat(result).contains(request);
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
    }

    @Test
    @DisplayName("이미 실패 처리된 질문은 AI를 호출하지 않는다")
    void skipsQuestionNoLongerAccepted() {
        aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);
        when(messages.findWithRequestByAiRequestId(900L)).thenReturn(Optional.of(message));

        assertThat(service.startProcessing(900L)).isEmpty();
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        verify(factory, never()).create(any());
    }

    @Test
    @DisplayName("질문이 없으면 아무것도 하지 않는다")
    void skipsMissingQuestion() {
        when(messages.findWithRequestByAiRequestId(900L)).thenReturn(Optional.empty());

        assertThat(service.startProcessing(900L)).isEmpty();
    }
}
