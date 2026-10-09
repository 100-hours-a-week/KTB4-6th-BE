package com.backend.meety.domain.ai.service;

import static com.backend.meety.domain.recording.RecordingFixtures.CLOCK;
import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.credit;
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

import com.backend.meety.domain.ai.ChatPolicy;
import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.ChatInputType;
import com.backend.meety.domain.ai.event.ChatCompletedEvent;
import com.backend.meety.domain.ai.event.ChatFailedEvent;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import com.backend.meety.domain.credit.TestCreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

class ChatProcessingServiceTest {

    private final AiChatbotMessageRepository messages = mock(AiChatbotMessageRepository.class);
    private final ChatAiRequestFactory factory = mock(ChatAiRequestFactory.class);
    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final TeamCreditRepository credits = mock(TeamCreditRepository.class);
    private final CreditLedgerRepository ledgers = mock(CreditLedgerRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final ChatProcessingService service = new ChatProcessingService(
            messages, factory, meetings, credits, new ChatFailureHandler(ledgers, TestCreditPolicy.DEFAULT),
            events, CLOCK);

    private final Team team = team();
    private final TeamMember member = member(team);
    private final Meeting meeting = meeting(team, member);
    private final AiRequest aiRequest = withId(AiRequest.create(team, member, "key", AiRequestType.CHAT), 900L);
    private final AiChatbotMessage message = withId(
            AiChatbotMessage.create(aiRequest, meeting, member, ChatInputType.TEXT, "질문"), 8801L);
    private TeamCredit credit;

    @BeforeEach
    void setUp() {
        credit = credit(team, 9L);
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(credit));
        when(messages.findWithRequestByAiRequestId(900L)).thenReturn(Optional.of(message));
        when(ledgers.existsByIdempotencyKey("USE:AI_CHAT:900")).thenReturn(true);
    }

    @Test
    @DisplayName("접수 상태 질문은 PROCESSING으로 바꾸고 AI 요청을 만든다")
    void startsAcceptedQuestion() {
        ChatAiRequest request = new ChatAiRequest(900L, 100L, 2L, "질문", List.of(), List.of());
        when(factory.create(message)).thenReturn(request);

        Optional<ChatAiRequest> result = service.startProcessing(900L);

        assertThat(result).contains(request);
        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
    }

    @Test
    @DisplayName("이미 실패 처리된 질문은 AI를 호출하지 않는다")
    void skipsQuestionNoLongerAccepted() {
        aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);

        assertThat(service.startProcessing(900L)).isEmpty();
        verify(factory, never()).create(any());
    }

    @Test
    @DisplayName("처리 중 질문에 답변이 오면 답변·답변 시각·빈 근거를 저장하고 COMPLETED로 바꾼다")
    void completesProcessingQuestion() {
        aiRequest.markProcessing();

        service.complete(2L, 900L, "**금요일 배포**로 결정했습니다.");

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
        assertThat(message.getAnswer()).isEqualTo("**금요일 배포**로 결정했습니다.");
        assertThat(message.getCitations()).isEqualTo(ChatPolicy.EMPTY_CITATIONS);
        assertThat(message.getAnsweredAt()).isEqualTo(NOW);
        verify(events).publishEvent(new ChatCompletedEvent(100L, 8801L));
    }

    @Test
    @DisplayName("이미 실패·취소된 질문에 늦게 온 답변은 저장하지 않는다")
    void ignoresLateAnswer() {
        aiRequest.markFailed(AiFailureReason.AI_CALL_FAILED);

        service.complete(2L, 900L, "늦은 답변");

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(message.getAnswer()).isNull();
        verify(events, never()).publishEvent(any());
    }

    @Test
    @DisplayName("처리 중 질문이 실패하면 FAILED로 바꾸고 크레딧을 돌려준 뒤 복구 후 잔액으로 알린다")
    void failsProcessingQuestion() {
        aiRequest.markProcessing();

        service.fail(2L, 900L);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(aiRequest.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
        assertThat(credit.getBalance()).isEqualTo(10L);
        ArgumentCaptor<CreditLedger> ledger = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers).save(ledger.capture());
        assertThat(ledger.getValue().getIdempotencyKey()).isEqualTo("RESTORE:AI_CHAT:900");
        verify(events).publishEvent(new ChatFailedEvent(100L, 8801L, 10L));
    }

    @Test
    @DisplayName("이미 완료된 질문의 실패 알림은 무시해 크레딧을 두 번 돌려주지 않는다")
    void ignoresFailureOfCompletedQuestion() {
        aiRequest.markCompleted();

        service.fail(2L, 900L);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.COMPLETED);
        assertThat(credit.getBalance()).isEqualTo(9L);
        verify(ledgers, never()).save(any());
    }

    @Test
    @DisplayName("회의가 끝나면 처리 중 질문을 모두 실패 처리하고 크레딧을 돌려준다")
    void cancelsProcessingQuestionsOnMeetingEnd() {
        aiRequest.markProcessing();
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(messages.findByMeetingIdAndAiRequestStatusIn(100L, ChatPolicy.PROCESSING_STATUSES))
                .thenReturn(List.of(message));

        service.cancelProcessing(100L);

        assertThat(aiRequest.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(credit.getBalance()).isEqualTo(10L);
        verify(events).publishEvent(new ChatFailedEvent(100L, 8801L, 10L));
    }

    @Test
    @DisplayName("처리 중 질문이 없으면 아무것도 바꾸지 않는다")
    void cancelsNothingWithoutProcessingQuestion() {
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(messages.findByMeetingIdAndAiRequestStatusIn(100L, ChatPolicy.PROCESSING_STATUSES))
                .thenReturn(List.of());

        service.cancelProcessing(100L);

        verify(ledgers, never()).save(any());
        verify(events, never()).publishEvent(any());
    }
}
