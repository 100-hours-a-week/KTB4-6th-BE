package com.backend.meety.domain.ai.assistant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.AssistantChatConversation;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.ai.repository.AssistantChatConversationRepository;
import com.backend.meety.domain.ai.repository.AssistantChatTurnRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AssistantChatServiceTest {

    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final AssistantChatConversationRepository conversations = mock(AssistantChatConversationRepository.class);
    private final AssistantChatTurnRepository turns = mock(AssistantChatTurnRepository.class);
    private final AiRequestRepository requests = mock(AiRequestRepository.class);
    private final Team team = mock(Team.class);
    private final TeamMember member = mock(TeamMember.class);
    private final AssistantChatConversation conversation = mock(AssistantChatConversation.class);
    private final AssistantChatService service = new AssistantChatService(
            members, conversations, turns, requests,
            new AssistantChatProperties(Duration.ofSeconds(180), Duration.ofSeconds(200),
                    Duration.ofSeconds(30), 20, 20, 50),
            new ObjectMapper(), mock(Validator.class),
            Clock.fixed(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(members.findActiveByTeamAndUserForUpdate(5L, 9L)).thenReturn(Optional.of(member));
        when(member.getId()).thenReturn(12L);
        when(member.getTeam()).thenReturn(team);
        when(conversations.findForUpdate(5L, 12L)).thenReturn(Optional.of(conversation));
        when(turns.findProcessing(any(), any())).thenReturn(List.of());
        when(requests.findByIdempotencyKey(any())).thenReturn(Optional.empty());
        when(requests.saveAndFlush(any(AiRequest.class))).thenAnswer(invocation -> {
            AiRequest request = invocation.getArgument(0);
            ReflectionTestUtils.setField(request, "id", 44L);
            return request;
        });
        when(turns.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void storesGlobalChatRequestWithoutCreditDependencyAndReturnsAcceptedRequest() {
        AssistantChatSubmission submission = service.submit(9L, 5L, "client-key", "회의를 분석해줘");

        assertThat(submission.requestId()).isEqualTo(44L);
        assertThat(submission.created()).isTrue();
        verify(requests).saveAndFlush(any(AiRequest.class));
        verify(turns).saveAndFlush(any());
        verify(members).findActiveByTeamAndUserForUpdate(5L, 9L);
    }

    @Test
    void rejectsSecondInFlightQuestionForTheSameConversation() {
        var existing = mock(com.backend.meety.domain.ai.entity.AssistantChatTurn.class);
        var existingRequest = mock(AiRequest.class);
        when(existing.getAiRequest()).thenReturn(existingRequest);
        when(existingRequest.isProcessing()).thenReturn(false);
        when(turns.findProcessing(any(), any())).thenReturn(List.of(existing));

        assertThatThrownBy(() -> service.submit(9L, 5L, "another-key", "새 질문"))
                .isInstanceOf(AssistantChatException.class)
                .hasMessageContaining("처리 중인 요청");

        verify(requests, never()).saveAndFlush(any(AiRequest.class));
        verify(turns, never()).saveAndFlush(any());
    }

    @Test
    void recordsDedicatedRequestType() {
        service.submit(9L, 5L, "client-key", "분석해줘");

        verify(requests).saveAndFlush(org.mockito.ArgumentMatchers.argThat(
                request -> request.getRequestType() == AiRequestType.GLOBAL_CHAT
                        && request.getStatus() == AiRequestStatus.ACCEPTED));
    }

    @Test
    void claimUsesTeamMemberLinkedToAiRequestAsRequesterId() {
        TeamMember requester = mock(TeamMember.class);
        when(requester.getId()).thenReturn(12L);
        TeamMember conversationMember = mock(TeamMember.class);
        when(conversationMember.getId()).thenReturn(99L);
        when(conversation.getTeamMember()).thenReturn(conversationMember);
        AiRequest request = mock(AiRequest.class);
        when(request.getTeamMember()).thenReturn(requester);
        var turn = mock(com.backend.meety.domain.ai.entity.AssistantChatTurn.class);
        when(turn.getAiRequest()).thenReturn(request);
        when(turn.getConversation()).thenReturn(conversation);
        when(conversation.getId()).thenReturn(33L);
        when(conversation.getTeam()).thenReturn(team);
        when(team.getId()).thenReturn(5L);
        when(turn.getQuestion()).thenReturn("나랑 회의 잡아줘");
        when(turn.getId()).thenReturn(44L);
        when(requests.claimAccepted(org.mockito.ArgumentMatchers.eq(44L), anyString())).thenReturn(1);
        when(turns.findWithContextByRequestId(44L)).thenReturn(Optional.of(turn));
        when(turns.findRecentForContext(org.mockito.ArgumentMatchers.eq(33L),
                org.mockito.ArgumentMatchers.eq(44L),
                org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Limit.class)))
                .thenReturn(List.of());

        AssistantChatWork work = service.claim(44L);

        assertThat(work.requesterTeamMemberId()).isEqualTo(12L);
        assertThat(work.teamId()).isEqualTo(5L);
        assertThat(work.requestId()).isEqualTo(44L);
        assertThat(work.question()).isEqualTo("나랑 회의 잡아줘");
        assertThat(work.recentMessages()).isEmpty();
    }

    @Test
    void claimDoesNotCreateAiWorkWhenRequesterTeamMemberIsMissing() {
        AiRequest request = mock(AiRequest.class);
        when(request.getTeamMember()).thenReturn(null);
        var turn = mock(com.backend.meety.domain.ai.entity.AssistantChatTurn.class);
        when(turn.getAiRequest()).thenReturn(request);
        when(turn.getConversation()).thenReturn(conversation);
        when(conversation.getId()).thenReturn(33L);
        when(turn.getId()).thenReturn(44L);
        when(requests.claimAccepted(org.mockito.ArgumentMatchers.eq(44L), anyString())).thenReturn(1);
        when(turns.findWithContextByRequestId(44L)).thenReturn(Optional.of(turn));

        assertThatThrownBy(() -> service.claim(44L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no requester team member");
        verify(turns, never()).findRecentForContext(any(), any(), any());
    }
}
