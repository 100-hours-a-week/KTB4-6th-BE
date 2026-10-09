package com.backend.meety.domain.ai.service;

import com.backend.meety.domain.ai.ChatPolicy;
import com.backend.meety.domain.ai.client.ChatAiRequest;
import com.backend.meety.domain.ai.entity.AiChatbotMessage;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.event.ChatCompletedEvent;
import com.backend.meety.domain.ai.event.ChatFailedEvent;
import com.backend.meety.domain.ai.repository.AiChatbotMessageRepository;
import com.backend.meety.domain.credit.entity.TeamCredit;
import com.backend.meety.domain.credit.exception.CreditErrorCode;
import com.backend.meety.domain.credit.exception.CreditException;
import com.backend.meety.domain.credit.repository.TeamCreditRepository;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatProcessingService {

    private final AiChatbotMessageRepository chatbotMessageRepository;
    private final ChatAiRequestFactory chatAiRequestFactory;
    private final MeetingRepository meetingRepository;
    private final TeamCreditRepository teamCreditRepository;
    private final ChatFailureHandler chatFailureHandler;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    @Transactional
    public Optional<ChatAiRequest> startProcessing(Long aiRequestId) {
        return chatbotMessageRepository.findWithRequestByAiRequestId(aiRequestId)
                .filter(message -> message.getAiRequest().isAccepted())
                .map(this::markProcessing);
    }

    @Transactional
    public void complete(Long teamId, Long aiRequestId, String answer) {
        lockCredit(teamId);
        findProcessingMessage(aiRequestId, ChatResultType.ANSWER)
                .ifPresent(message -> completeMessage(message, answer));
    }

    @Transactional
    public void fail(Long teamId, Long aiRequestId) {
        TeamCredit credit = lockCredit(teamId);
        findProcessingMessage(aiRequestId, ChatResultType.FAILURE)
                .ifPresent(message -> failMessage(message, credit));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.READ_COMMITTED)
    public void cancelProcessing(Long meetingId) {
        meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .ifPresent(this::cancelMeetingMessages);
    }

    private ChatAiRequest markProcessing(AiChatbotMessage message) {
        message.getAiRequest().markProcessing();
        return chatAiRequestFactory.create(message);
    }

    private Optional<AiChatbotMessage> findProcessingMessage(Long aiRequestId, ChatResultType resultType) {
        Optional<AiChatbotMessage> message = chatbotMessageRepository.findWithRequestByAiRequestId(aiRequestId);
        if (message.isEmpty()) {
            log.warn("처리할 AI 질문이 없습니다. aiRequestId={}, result={}", aiRequestId, resultType);
            return Optional.empty();
        }
        AiRequest request = message.get().getAiRequest();
        if (!request.isProcessing()) {
            log.info("이미 처리가 끝난 AI 질문의 결과를 무시합니다. aiRequestId={}, result={}, status={}",
                    aiRequestId, resultType, request.getStatus());
            return Optional.empty();
        }
        return message;
    }

    private void completeMessage(AiChatbotMessage message, String answer) {
        message.complete(answer, ChatPolicy.EMPTY_CITATIONS, LocalDateTime.now(clock));
        message.getAiRequest().markCompleted();
        eventPublisher.publishEvent(new ChatCompletedEvent(message.getMeeting().getId(), message.getId()));
    }

    private void cancelMeetingMessages(Meeting meeting) {
        TeamCredit credit = lockCredit(meeting.getTeam().getId());
        chatbotMessageRepository.findByMeetingIdAndAiRequestStatusIn(meeting.getId(), ChatPolicy.PROCESSING_STATUSES)
                .forEach(message -> failMessage(message, credit));
    }

    private void failMessage(AiChatbotMessage message, TeamCredit credit) {
        chatFailureHandler.fail(message.getAiRequest(), credit);
        eventPublisher.publishEvent(new ChatFailedEvent(
                message.getMeeting().getId(), message.getId(), credit.getBalance()));
    }

    private TeamCredit lockCredit(Long teamId) {
        return teamCreditRepository.findByTeamIdForUpdate(teamId)
                .orElseThrow(() -> {
                    log.error("AI 질문 처리에 필요한 팀 크레딧 행이 없습니다. teamId={}", teamId);
                    return new CreditException(CreditErrorCode.TEAM_CREDIT_NOT_FOUND);
                });
    }
}
