package com.backend.meety.domain.ai.service;

import static com.backend.meety.domain.recording.RecordingFixtures.CLOCK;
import static com.backend.meety.domain.recording.RecordingFixtures.NOW;
import static com.backend.meety.domain.recording.RecordingFixtures.credit;
import static com.backend.meety.domain.recording.RecordingFixtures.meeting;
import static com.backend.meety.domain.recording.RecordingFixtures.member;
import static com.backend.meety.domain.recording.RecordingFixtures.team;
import static com.backend.meety.domain.recording.RecordingFixtures.withId;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.backend.meety.domain.ai.ChatPolicy;
import com.backend.meety.domain.ai.dto.ChatCreateResponse;
import com.backend.meety.domain.ai.dto.ChatListResponse;
import com.backend.meety.domain.ai.dto.ChatMessageResponse;
import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiFailureReason;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.ChatInputType;
import com.backend.meety.domain.ai.event.ChatRequestedEvent;
import com.backend.meety.domain.ai.exception.AiChatErrorCode;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.credit.TestCreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.CreditTransactionType;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.Team;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.global.exception.BaseCode;
import com.backend.meety.global.exception.BusinessException;
import com.backend.meety.global.exception.CommonErrorCode;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.test.util.ReflectionTestUtils;

class AiChatServiceTest {

    private static final String IDEMPOTENCY_KEY = "11111111-2222-3333-4444-555555555555";
    private static final List<AiRequestStatus> PROCESSING = ChatPolicy.PROCESSING_STATUSES;

    private final MeetingRepository meetings = mock(MeetingRepository.class);
    private final TeamMemberRepository members = mock(TeamMemberRepository.class);
    private final MeetingParticipantRepository participants = mock(MeetingParticipantRepository.class);
    private final AiRequestRepository aiRequests = mock(AiRequestRepository.class);
    private final AiChatbotMessageRepository messages = mock(AiChatbotMessageRepository.class);
    private final TeamCreditRepository credits = mock(TeamCreditRepository.class);
    private final CreditLedgerRepository ledgers = mock(CreditLedgerRepository.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final AiChatService service = new AiChatService(
            meetings, members, participants, aiRequests, messages, credits, ledgers,
            TestCreditPolicy.DEFAULT, events, CLOCK);

    private Team team;
    private TeamMember member;
    private Meeting meeting;
    private TeamCredit credit;

    @BeforeEach
    void setUp() {
        team = team();
        member = member(team);
        meeting = meeting(team, member);
        meeting.start(NOW);
        credit = credit(team, 10L);
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting));
        when(aiRequests.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.empty());
        when(members.findByTeamIdAndUserIdAndMembershipStatus(2L, 1L, MembershipStatus.ACTIVE))
                .thenReturn(Optional.of(member));
        when(participants.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                100L, 10L, ParticipationStatus.JOINED)).thenReturn(true);
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(credit));
        when(messages.findByMeetingIdAndAiRequestStatusIn(100L, PROCESSING)).thenReturn(List.of());
        when(aiRequests.save(any(AiRequest.class))).thenAnswer(call -> withId(call.getArgument(0), 900L));
        when(messages.save(any(AiChatbotMessage.class))).thenAnswer(call -> withId(call.getArgument(0), 8801L));
    }

    @Test
    @DisplayName("질문을 접수하면 크레딧 1을 차감하고 ACCEPTED 질문을 저장한 뒤 이벤트를 발행한다")
    void acceptsQuestion() {
        ChatCreateResponse response = request();

        assertThat(response.messageId()).isEqualTo(8801L);
        assertThat(response.status()).isEqualTo(AiRequestStatus.ACCEPTED);
        assertThat(response.creditBalance()).isEqualTo(9L);

        ArgumentCaptor<AiRequest> request = ArgumentCaptor.forClass(AiRequest.class);
        verify(aiRequests).save(request.capture());
        assertThat(request.getValue().getRequestType()).isEqualTo(AiRequestType.CHAT);
        assertThat(request.getValue().getIdempotencyKey()).isEqualTo(IDEMPOTENCY_KEY);

        ArgumentCaptor<CreditLedger> ledger = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers).save(ledger.capture());
        assertThat(ledger.getValue().getIdempotencyKey()).isEqualTo("USE:AI_CHAT:900");
        assertThat(ledger.getValue().getAmount()).isEqualTo(-1L);
        assertThat(ledger.getValue().getBalanceAfter()).isEqualTo(9L);

        verify(events).publishEvent(new ChatRequestedEvent(900L, 100L));
    }

    @Test
    @DisplayName("같은 Idempotency-Key로 다시 요청하면 기존 질문 상태를 그대로 반환한다")
    void replaysSameIdempotencyKey() {
        AiRequest existing = withId(AiRequest.create(team, member, IDEMPOTENCY_KEY, AiRequestType.CHAT), 900L);
        AiChatbotMessage message = withId(
                AiChatbotMessage.create(existing, meeting, member, ChatInputType.TEXT, "질문"), 8801L);
        when(aiRequests.findByIdempotencyKey(IDEMPOTENCY_KEY)).thenReturn(Optional.of(existing));
        when(messages.findByAiRequestId(900L)).thenReturn(Optional.of(message));

        ChatCreateResponse response = request();

        assertThat(response.messageId()).isEqualTo(8801L);
        assertThat(response.creditBalance()).isEqualTo(10L);
        verify(aiRequests, never()).save(any());
        verify(ledgers, never()).save(any());
    }

    @Test
    @DisplayName("Idempotency-Key가 비어 있거나 100자를 넘으면 회의를 조회하지 않고 거절한다")
    void rejectsInvalidIdempotencyKey() {
        assertCode(() -> service.requestChat(1L, 100L, " ", ChatInputType.TEXT, "질문"),
                CommonErrorCode.INVALID_INPUT_VALUE);
        assertCode(() -> service.requestChat(1L, 100L, "k".repeat(101), ChatInputType.TEXT, "질문"),
                CommonErrorCode.INVALID_INPUT_VALUE);
        verify(meetings, never()).findByIdAndDeletedAtIsNull(any());
    }

    @Test
    @DisplayName("진행 중이 아닌 회의에는 질문할 수 없다")
    void rejectsMeetingNotInProgress() {
        Meeting waiting = meeting(team, member);
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(waiting));

        assertCode(this::request, AiChatErrorCode.MEETING_NOT_IN_PROGRESS);
        verify(ledgers, never()).save(any());
    }

    @Test
    @DisplayName("회의에 참여 중이 아닌 팀원은 질문할 수 없다")
    void rejectsNonParticipant() {
        when(participants.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                100L, 10L, ParticipationStatus.JOINED)).thenReturn(false);

        assertCode(this::request, MeetingErrorCode.MEETING_ACCESS_DENIED);
    }

    @Test
    @DisplayName("60초가 지나지 않은 처리 중 질문이 있으면 새 질문을 거절하고 차감하지 않는다")
    void rejectsWhileProcessing() {
        AiRequest processing = processingRequest(NOW.minusSeconds(30));
        when(messages.findByMeetingIdAndAiRequestStatusIn(100L, PROCESSING))
                .thenReturn(List.of(AiChatbotMessage.create(processing, meeting, member, ChatInputType.TEXT, "앞 질문")));

        assertCode(this::request, AiChatErrorCode.AI_MESSAGE_ALREADY_PROCESSING);
        assertThat(processing.getStatus()).isEqualTo(AiRequestStatus.PROCESSING);
        assertThat(credit.getBalance()).isEqualTo(10L);
        verify(ledgers, never()).save(any());
    }

    @Test
    @DisplayName("60초가 지난 처리 중 질문은 실패 처리하고 크레딧을 돌려준 뒤 새 질문을 접수한다")
    void expiresStaleProcessingQuestion() {
        AiRequest stale = processingRequest(NOW.minusSeconds(61));
        when(messages.findByMeetingIdAndAiRequestStatusIn(100L, PROCESSING))
                .thenReturn(List.of(AiChatbotMessage.create(stale, meeting, member, ChatInputType.TEXT, "앞 질문")));
        when(ledgers.existsByIdempotencyKey("USE:AI_CHAT:800")).thenReturn(true);

        ChatCreateResponse response = request();

        assertThat(stale.getStatus()).isEqualTo(AiRequestStatus.FAILED);
        assertThat(stale.getFailureReason()).isEqualTo(AiFailureReason.AI_CALL_FAILED);
        assertThat(response.creditBalance()).isEqualTo(10L);

        ArgumentCaptor<CreditLedger> ledger = ArgumentCaptor.forClass(CreditLedger.class);
        verify(ledgers, times(2)).save(ledger.capture());
        CreditLedger restore = ledger.getAllValues().getFirst();
        assertThat(restore.getIdempotencyKey()).isEqualTo("RESTORE:AI_CHAT:800");
        assertThat(restore.getType()).isEqualTo(CreditTransactionType.RESTORE);
        assertThat(restore.getAmount()).isEqualTo(1L);
    }

    @Test
    @DisplayName("팀 크레딧이 부족하면 질문을 저장하지 않는다")
    void rejectsInsufficientCredit() {
        when(credits.findByTeamIdForUpdate(2L)).thenReturn(Optional.of(credit(team, 0L)));

        assertCode(this::request, CreditErrorCode.INSUFFICIENT_CREDIT);
        verify(aiRequests, never()).save(any());
    }

    @Test
    @DisplayName("목록은 size보다 하나 더 조회해 다음 페이지가 있으면 마지막 메시지 ID를 커서로 준다")
    void listsFirstPageWithNextCursor() {
        when(messages.findPageByMeetingId(100L, 0L, Limit.of(3)))
                .thenReturn(List.of(chatMessage(8801L), chatMessage(8802L), chatMessage(8803L)));

        ChatListResponse response = service.getChats(1L, 100L, null, 2);

        assertThat(response.messages()).extracting(ChatMessageResponse::messageId).containsExactly(8801L, 8802L);
        assertThat(response.hasNext()).isTrue();
        assertThat(response.nextCursor()).isEqualTo(8802L);
    }

    @Test
    @DisplayName("마지막 페이지와 빈 목록은 다음 커서 없이 반환한다")
    void listsLastPageAndEmptyPage() {
        when(messages.findPageByMeetingId(100L, 8802L, Limit.of(ChatPolicy.DEFAULT_PAGE_SIZE + 1)))
                .thenReturn(List.of(chatMessage(8803L)));
        when(messages.findPageByMeetingId(100L, 8803L, Limit.of(ChatPolicy.DEFAULT_PAGE_SIZE + 1)))
                .thenReturn(List.of());

        ChatListResponse last = service.getChats(1L, 100L, 8802L, null);
        ChatListResponse empty = service.getChats(1L, 100L, 8803L, null);

        assertThat(last.messages()).hasSize(1);
        assertThat(last.hasNext()).isFalse();
        assertThat(last.nextCursor()).isNull();
        assertThat(empty.messages()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
    }

    @Test
    @DisplayName("커서가 1보다 작거나 페이지 크기가 1~50 밖이면 거절한다")
    void rejectsInvalidCursorAndPageSize() {
        assertCode(() -> service.getChats(1L, 100L, 0L, null), MeetingErrorCode.INVALID_CURSOR);
        assertCode(() -> service.getChats(1L, 100L, null, 0), MeetingErrorCode.INVALID_PAGE_SIZE);
        assertCode(() -> service.getChats(1L, 100L, null, 51), MeetingErrorCode.INVALID_PAGE_SIZE);
    }

    @Test
    @DisplayName("회의 참여자가 아니거나 진행 중인 회의가 아니면 목록을 조회할 수 없다")
    void rejectsListForNonParticipantOrNotInProgress() {
        when(participants.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                100L, 10L, ParticipationStatus.JOINED)).thenReturn(false);
        assertCode(() -> service.getChats(1L, 100L, null, null), MeetingErrorCode.MEETING_ACCESS_DENIED);

        when(participants.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                100L, 10L, ParticipationStatus.JOINED)).thenReturn(true);
        when(meetings.findByIdAndDeletedAtIsNull(100L)).thenReturn(Optional.of(meeting(team, member)));
        assertCode(() -> service.getChats(1L, 100L, null, null), AiChatErrorCode.MEETING_NOT_IN_PROGRESS);
    }

    private AiChatbotMessage chatMessage(long messageId) {
        AiRequest request = withId(AiRequest.create(team, member, "key-" + messageId, AiRequestType.CHAT), messageId);
        return withId(AiChatbotMessage.create(request, meeting, member, ChatInputType.TEXT, "질문"), messageId);
    }

    private ChatCreateResponse request() {
        return service.requestChat(1L, 100L, IDEMPOTENCY_KEY, ChatInputType.TEXT, "지금까지 결정된 사항 정리해줘");
    }

    private AiRequest processingRequest(LocalDateTime createdAt) {
        AiRequest request = withId(AiRequest.create(team, member, "earlier-key", AiRequestType.CHAT), 800L);
        request.markProcessing();
        ReflectionTestUtils.setField(request, "createdAt", createdAt);
        return request;
    }

    private void assertCode(Runnable action, BaseCode expected) {
        assertThatThrownBy(action::run).isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(expected);
    }
}
