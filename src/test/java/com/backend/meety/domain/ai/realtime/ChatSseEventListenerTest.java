package com.backend.meety.domain.ai.realtime;

import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.ChatInputType;
import com.backend.meety.domain.ai.event.ChatCompletedEvent;
import com.backend.meety.domain.ai.event.ChatFailedEvent;
import com.backend.meety.domain.ai.event.ChatRequestedEvent;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import com.backend.meety.domain.meeting.realtime.MeetingSseRegistry;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ChatSseEventListenerTest {

    private final AiChatbotMessageRepository messages = mock(AiChatbotMessageRepository.class);
    private final MeetingSseRegistry registry = mock(MeetingSseRegistry.class);
    private final ChatSseEventListener listener = new ChatSseEventListener(messages, registry);

    @Test
    @DisplayName("질문이 접수되면 회의 참여자 전원에게 CHAT_REQUESTED를 보낸다")
    void broadcastsChatRequested() {
        Team team = team();
        TeamMember member = member(team);
        AiRequest request = withId(AiRequest.create(team, member, "key", AiRequestType.CHAT), 900L);
        AiChatbotMessage message = withId(
                AiChatbotMessage.create(request, meeting(team, member), member, ChatInputType.TEXT, "질문"), 8801L);
        when(messages.findWithAskerById(8801L)).thenReturn(Optional.of(message));

        listener.broadcastRequested(new ChatRequestedEvent(900L, 100L, 8801L, 9L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).broadcast(eq(100L), eq("CHAT_REQUESTED"), payload.capture());
        ChatRequestedSseEvent event = (ChatRequestedSseEvent) payload.getValue();
        assertThat(event.type()).isEqualTo("CHAT_REQUESTED");
        assertThat(event.messageId()).isEqualTo(8801L);
        assertThat(event.askerTeamMemberId()).isEqualTo(10L);
        assertThat(event.askerDisplayName()).isEqualTo("시작자");
        assertThat(event.question()).isEqualTo("질문");
        assertThat(event.creditBalance()).isEqualTo(9L);
    }

    @Test
    @DisplayName("질문을 찾지 못하면 보내지 않는다")
    void skipsMissingMessage() {
        when(messages.findWithAskerById(8801L)).thenReturn(Optional.empty());

        listener.broadcastRequested(new ChatRequestedEvent(900L, 100L, 8801L, 9L));

        verify(registry, never()).broadcast(anyLong(), anyString(), any());
    }

    @Test
    @DisplayName("답변이 저장되면 CHAT_COMPLETED로 답변·근거·답변 시각을 보낸다")
    void broadcastsChatCompleted() {
        Team team = team();
        TeamMember member = member(team);
        AiRequest request = withId(AiRequest.create(team, member, "key", AiRequestType.CHAT), 900L);
        AiChatbotMessage message = withId(
                AiChatbotMessage.create(request, meeting(team, member), member, ChatInputType.TEXT, "질문"), 8801L);
        message.complete("답변", "[]", NOW);
        when(messages.findById(8801L)).thenReturn(Optional.of(message));

        listener.broadcastCompleted(new ChatCompletedEvent(100L, 8801L));

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(registry).broadcast(eq(100L), eq("CHAT_COMPLETED"), payload.capture());
        ChatCompletedSseEvent event = (ChatCompletedSseEvent) payload.getValue();
        assertThat(event.messageId()).isEqualTo(8801L);
        assertThat(event.answer()).isEqualTo("답변");
        assertThat(event.citations()).isEqualTo("[]");
        assertThat(event.answeredAt()).isEqualTo(NOW);
    }

    @Test
    @DisplayName("질문이 실패하면 CHAT_FAILED로 복구 후 잔액을 보낸다")
    void broadcastsChatFailed() {
        listener.broadcastFailed(new ChatFailedEvent(100L, 8801L, 10L));

        verify(registry).broadcast(100L, "CHAT_FAILED",
                new ChatFailedSseEvent("CHAT_FAILED", 100L, 8801L, 10L));
    }
}
