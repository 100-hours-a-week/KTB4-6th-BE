package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.ChatPolicy;
import com.backend.meety.domain.ai.dto.ChatCreateResponse;
import com.backend.meety.domain.ai.dto.ChatListResponse;
import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.ChatInputType;
import com.backend.meety.domain.ai.event.ChatFailedEvent;
import com.backend.meety.domain.ai.event.ChatRequestedEvent;
import com.backend.meety.domain.ai.exception.AiChatErrorCode;
import com.backend.meety.domain.ai.exception.AiChatException;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.credit.CreditPolicy;
import com.backend.meety.domain.credit.entity.CreditLedger;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.exception.CreditException;
import com.backend.meety.domain.credit.repository.CreditLedgerRepository;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.entity.MeetingStatus;
import com.backend.meety.domain.meeting.entity.ParticipationStatus;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.global.exception.BusinessException;
import com.backend.meety.global.exception.CommonErrorCode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AiChatService {

    private final MeetingRepository meetingRepository;
    private final TeamMemberRepository teamMemberRepository;
    private final MeetingParticipantRepository participantRepository;
    private final AiRequestRepository aiRequestRepository;
    private final AiChatbotMessageRepository chatbotMessageRepository;
    private final TeamCreditRepository teamCreditRepository;
    private final CreditLedgerRepository creditLedgerRepository;
    private final CreditPolicy creditPolicy;
    private final ChatFailureHandler chatFailureHandler;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ChatCreateResponse requestChat(Long userId, Long meetingId, String idempotencyKey,
                                          ChatInputType inputType, String question) {
        validateIdempotencyKey(idempotencyKey);
        Meeting meeting = findMeeting(meetingId);
        TeamCredit credit = lockCredit(meeting.getTeam().getId());
        return aiRequestRepository.findByIdempotencyKey(idempotencyKey)
                .map(acceptedRequest -> ChatCreateResponse.of(findMessage(acceptedRequest), credit.getBalance()))
                .orElseGet(() -> acceptQuestion(userId, meeting, credit, idempotencyKey, inputType, question));
    }

    @Transactional(readOnly = true)
    public ChatListResponse getChats(Long userId, Long meetingId, Long cursor, Integer size) {
        long beforeMessageId = resolveCursor(cursor);
        int pageSize = resolvePageSize(size);
        Meeting meeting = findMeeting(meetingId);
        TeamMember member = findParticipant(userId, meeting);
        validateInProgress(meeting);
        List<AiChatbotMessage> fetched = chatbotMessageRepository.findPageByMeetingId(
                meetingId, beforeMessageId, Limit.of(pageSize + 1));
        boolean hasAskedQuestion = chatbotMessageRepository.existsByMeetingIdAndTeamMemberIdAndDeletedAtIsNull(
                meetingId, member.getId());
        return ChatListResponse.of(fetched, pageSize, hasAskedQuestion);
    }

    private ChatCreateResponse acceptQuestion(Long userId, Meeting meeting, TeamCredit credit,
                                              String idempotencyKey, ChatInputType inputType, String question) {
        TeamMember asker = findParticipant(userId, meeting);
        validateInProgress(meeting);
        List<AiChatbotMessage> processingMessages = findProcessingMessages(meeting.getId());
        rejectIfProcessing(processingMessages);
        expireStaleMessages(processingMessages, credit);
        validateEnoughCredit(credit);
        AiRequest aiRequest = aiRequestRepository.save(
                AiRequest.create(meeting.getTeam(), asker, idempotencyKey, AiRequestType.CHAT));
        useCredit(credit, aiRequest);
        AiChatbotMessage message = chatbotMessageRepository.save(
                AiChatbotMessage.create(aiRequest, meeting, asker, inputType, question));

        eventPublisher.publishEvent(new ChatRequestedEvent(
                aiRequest.getId(), meeting.getId(), message.getId(), credit.getBalance()));
        return ChatCreateResponse.of(message, credit.getBalance());
    }

    private void validateIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()
                || idempotencyKey.length() > ChatPolicy.IDEMPOTENCY_KEY_MAX_LENGTH) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
    }

    private long resolveCursor(Long cursor) {
        if (cursor == null) {
            return Long.MAX_VALUE;
        }
        if (cursor < 1) {
            throw new MeetingException(MeetingErrorCode.INVALID_CURSOR);
        }
        return cursor;
    }

    private int resolvePageSize(Integer size) {
        if (size == null) {
            return ChatPolicy.DEFAULT_PAGE_SIZE;
        }
        if (size < 1 || size > ChatPolicy.MAX_PAGE_SIZE) {
            throw new MeetingException(MeetingErrorCode.INVALID_PAGE_SIZE);
        }
        return size;
    }

    private Meeting findMeeting(Long meetingId) {
        return meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
    }

    private TeamMember findParticipant(Long userId, Meeting meeting) {
        TeamMember member = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        meeting.getTeam().getId(), userId, MembershipStatus.ACTIVE)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED));
        if (!participantRepository.existsByMeetingIdAndTeamMemberIdAndParticipationStatusAndDeletedAtIsNull(
                meeting.getId(), member.getId(), ParticipationStatus.JOINED)) {
            throw new MeetingException(MeetingErrorCode.MEETING_ACCESS_DENIED);
        }
        return member;
    }

    private void validateInProgress(Meeting meeting) {
        if (meeting.getStatus() != MeetingStatus.IN_PROGRESS) {
            throw new AiChatException(AiChatErrorCode.MEETING_NOT_IN_PROGRESS);
        }
    }

    private void validateEnoughCredit(TeamCredit credit) {
        if (!credit.canUse(creditPolicy.aiChatMessageCost())) {
            throw new CreditException(CreditErrorCode.INSUFFICIENT_CREDIT);
        }
    }

    private void useCredit(TeamCredit credit, AiRequest aiRequest) {
        credit.use(creditPolicy.aiChatMessageCost());
        creditLedgerRepository.save(CreditLedger.useForChat(
                aiRequest.getTeam(), aiRequest.getId(), creditPolicy.aiChatMessageCost(), credit.getBalance()));
    }

    private TeamCredit lockCredit(Long teamId) {
        return teamCreditRepository.findByTeamIdForUpdate(teamId)
                .orElseThrow(() -> {
                    log.error("AI 질문에 필요한 팀 크레딧 행이 없습니다. teamId={}", teamId);
                    return new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND);
                });
    }

    private List<AiChatbotMessage> findProcessingMessages(Long meetingId) {
        return chatbotMessageRepository.findByMeetingIdAndAiRequestStatusIn(meetingId, ChatPolicy.PROCESSING_STATUSES);
    }

    private void rejectIfProcessing(List<AiChatbotMessage> processingMessages) {
        LocalDateTime timeoutDeadline = LocalDateTime.now(clock).minus(ChatPolicy.PROCESSING_TIMEOUT);
        if (processingMessages.stream()
                .anyMatch(message -> message.getAiRequest().getCreatedAt().isAfter(timeoutDeadline))) {
            throw new AiChatException(AiChatErrorCode.AI_MESSAGE_ALREADY_PROCESSING);
        }
    }

    private void expireStaleMessages(List<AiChatbotMessage> staleMessages, TeamCredit credit) {
        for (AiChatbotMessage message : staleMessages) {
            chatFailureHandler.fail(message.getAiRequest(), credit);
            eventPublisher.publishEvent(new ChatFailedEvent(
                    message.getMeeting().getId(), message.getId(), credit.getBalance()));
            log.warn("응답 기한이 지난 AI 질문을 실패 처리합니다. aiRequestId={}, meetingId={}",
                    message.getAiRequest().getId(), message.getMeeting().getId());
        }
    }

    private AiChatbotMessage findMessage(AiRequest aiRequest) {
        return chatbotMessageRepository.findByAiRequestId(aiRequest.getId())
                .orElseThrow(() -> new AiChatException(AiChatErrorCode.AI_MESSAGE_REQUEST_FAILED));
    }
}
