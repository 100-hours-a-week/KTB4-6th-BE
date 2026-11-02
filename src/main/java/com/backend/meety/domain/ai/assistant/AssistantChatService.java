package com.backend.meety.domain.ai.assistant;

import com.backend.meety.domain.ai.assistant.dto.AssistantChatPageResponse;
import com.backend.meety.domain.ai.assistant.dto.AssistantChatTurnResponse;
import com.backend.meety.domain.ai.client.ChatAiHistoryMessage;
import com.backend.meety.domain.ai.entity.AiRequest;
import com.backend.meety.domain.ai.entity.AiRequestStatus;
import com.backend.meety.domain.ai.entity.AiRequestType;
import com.backend.meety.domain.ai.entity.AssistantChatConversation;
import com.backend.meety.domain.ai.entity.AssistantChatTurn;
import com.backend.meety.domain.ai.repository.AiRequestRepository;
import com.backend.meety.domain.ai.repository.AssistantChatConversationRepository;
import com.backend.meety.domain.ai.repository.AssistantChatTurnRepository;
import com.backend.meety.domain.meeting.MeetingAttendeePolicy;
import com.backend.meety.domain.meeting.dto.MeetingCreateRequest;
import com.backend.meety.domain.team.entity.MembershipStatus;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.global.exception.BusinessException;
import com.backend.meety.global.exception.CommonErrorCode;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.Validator;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AssistantChatService {

    private static final List<AiRequestStatus> PROCESSING_STATUSES =
            List.of(AiRequestStatus.ACCEPTED, AiRequestStatus.PROCESSING);

    private final TeamMemberRepository teamMemberRepository;
    private final AssistantChatConversationRepository conversationRepository;
    private final AssistantChatTurnRepository turnRepository;
    private final AiRequestRepository aiRequestRepository;
    private final AssistantChatProperties properties;
    private final ObjectMapper objectMapper;
    private final Validator validator;
    private final Clock clock;

    @Transactional
    public AssistantChatSubmission submit(Long userId, Long teamId, String clientKey, String question) {
        if (clientKey == null || clientKey.isBlank() || clientKey.length() > 100) {
            throw new AssistantChatException(AssistantChatErrorCode.INVALID_IDEMPOTENCY_KEY);
        }
        if (question == null || question.isBlank() || question.length() > 1000) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        TeamMember member = teamMemberRepository.findActiveByTeamAndUserForUpdate(teamId, userId)
                .orElseThrow(() -> new AssistantChatException(AssistantChatErrorCode.CHAT_ACCESS_DENIED));
        String idempotencyKey = canonicalIdempotencyKey(teamId, member.getId(), clientKey);
        var previous = aiRequestRepository.findByIdempotencyKey(idempotencyKey);
        if (previous.isPresent()) {
            AiRequest request = previous.get();
            if (request.getRequestType() != AiRequestType.GLOBAL_CHAT) {
                throw new AssistantChatException(AssistantChatErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            AssistantChatTurn turn = turnRepository.findWithContextByRequestId(request.getId())
                    .orElseThrow(() -> new AssistantChatException(AssistantChatErrorCode.CHAT_NOT_FOUND));
            if (!turn.getQuestion().equals(question)) {
                throw new AssistantChatException(AssistantChatErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return new AssistantChatSubmission(request.getId(), turn.getConversation().getId(), false);
        }

        AssistantChatConversation conversation = conversationRepository
                .findForUpdate(teamId, member.getId())
                .orElseGet(() -> conversationRepository.saveAndFlush(
                        AssistantChatConversation.create(member.getTeam(), member)));
        expireStaleProcessing(conversation);
        if (!turnRepository.findProcessing(conversation.getId(), PROCESSING_STATUSES).isEmpty()) {
            throw new AssistantChatException(AssistantChatErrorCode.ACTIVE_REQUEST_EXISTS);
        }

        AiRequest request = aiRequestRepository.saveAndFlush(
                AiRequest.create(member.getTeam(), member, idempotencyKey, AiRequestType.GLOBAL_CHAT));
        turnRepository.saveAndFlush(AssistantChatTurn.create(request, conversation, question));
        return new AssistantChatSubmission(request.getId(), conversation.getId(), true);
    }

    @Transactional
    public AssistantChatWork claim(Long requestId) {
        String token = UUID.randomUUID().toString();
        if (aiRequestRepository.claimAccepted(requestId, token) != 1) return null;
        AssistantChatTurn current = turnRepository.findWithContextByRequestId(requestId)
                .orElseThrow(() -> new AssistantChatException(AssistantChatErrorCode.CHAT_NOT_FOUND));
        if (current.getAiRequest().getTeamMember() == null) {
            throw new IllegalStateException("GLOBAL_CHAT request has no requester team member");
        }
        List<AssistantChatTurn> previousTurns = turnRepository.findRecentForContext(
                current.getConversation().getId(), current.getId(), Limit.of(properties.historyMessageLimit()));
        List<ChatAiHistoryMessage> history = toRecentMessages(previousTurns,
                properties.historyMessageLimit());
        return new AssistantChatWork(requestId, token, current.getConversation().getTeam().getId(),
                current.getAiRequest().getTeamMember().getId(), current.getQuestion(), history);
    }

    @Transactional
    public AssistantChatTurnResponse complete(Long requestId, String processingToken, String answer,
                                              JsonNode rawMeetingDraft, List<Long> referencedMeetingIds) {
        if (aiRequestRepository.completeProcessing(requestId, processingToken) != 1) return null;
        AssistantChatTurn turn = turnRepository.findWithContextByRequestId(requestId)
                .orElseThrow(() -> new AssistantChatException(AssistantChatErrorCode.CHAT_NOT_FOUND));
        String draft = validateMeetingDraft(rawMeetingDraft, turn.getConversation().getTeam().getId());
        try {
            turn.complete(answer, draft, objectMapper.writeValueAsString(referencedMeetingIds));
        } catch (Exception e) {
            turn.complete(answer, draft, "[]");
        }
        return toResponse(turn);
    }

    @Transactional
    public boolean fail(Long requestId, String processingToken, String failureCode) {
        if (aiRequestRepository.failProcessing(requestId, processingToken) != 1) return false;
        turnRepository.findByAiRequestId(requestId).ifPresent(turn -> turn.fail(normalizeFailureCode(failureCode)));
        return true;
    }

    @Transactional
    public List<Long> failStaleProcessing() {
        LocalDateTime staleBefore = LocalDateTime.now(clock).minus(properties.streamTimeout());
        List<AssistantChatTurn> stale = turnRepository.findStaleProcessing(staleBefore, Limit.of(100));
        List<Long> failedRequestIds = new ArrayList<>();
        for (AssistantChatTurn candidate : stale) {
            AiRequest request = candidate.getAiRequest();
            if (aiRequestRepository.failProcessing(request.getId(), request.getProcessingToken()) == 1) {
                turnRepository.findByAiRequestId(request.getId())
                        .ifPresent(turn -> turn.fail("timeout"));
                failedRequestIds.add(request.getId());
            }
        }
        return List.copyOf(failedRequestIds);
    }

    @Transactional(readOnly = true)
    public AssistantChatTurnResponse getRequest(Long userId, Long requestId) {
        return turnRepository.findOwnedRequest(requestId, userId)
                .map(this::toResponse)
                .orElseThrow(() -> new AssistantChatException(AssistantChatErrorCode.CHAT_ACCESS_DENIED));
    }

    @Transactional(readOnly = true)
    public AssistantChatPageResponse getMessages(Long userId, Long teamId, Long cursor, Integer pageSize) {
        TeamMember member = teamMemberRepository.findByTeamIdAndUserIdAndMembershipStatus(
                        teamId, userId, MembershipStatus.ACTIVE)
                .filter(value -> value.getDeletedAt() == null)
                .orElseThrow(() -> new AssistantChatException(AssistantChatErrorCode.CHAT_ACCESS_DENIED));
        int size = pageSize == null ? properties.pageSize() : pageSize;
        if (size < 1 || size > properties.maxPageSize()) {
            throw new BusinessException(CommonErrorCode.INVALID_INPUT_VALUE);
        }
        long before = cursor == null ? Long.MAX_VALUE : cursor;
        if (before < 1) throw new BusinessException(CommonErrorCode.INVALID_INPUT_VALUE);
        var conversation = conversationRepository.findByTeamIdAndTeamMemberIdAndDeletedAtIsNull(teamId, member.getId());
        if (conversation.isEmpty()) return new AssistantChatPageResponse(List.of(), null, false);
        List<AssistantChatTurn> fetched = turnRepository.findPage(conversation.get().getId(), before,
                Limit.of(size + 1));
        boolean hasNext = fetched.size() > size;
        List<AssistantChatTurn> page = fetched.stream().limit(size).toList();
        Long nextCursor = hasNext && !page.isEmpty() ? page.getLast().getId() : null;
        return new AssistantChatPageResponse(page.stream().map(this::toResponse).toList(), nextCursor, hasNext);
    }


    public int dispatchAccepted(AssistantChatDispatcher dispatcher) {
        List<Long> ids = aiRequestRepository.findIdsByRequestTypeAndStatus(
                AiRequestType.GLOBAL_CHAT, AiRequestStatus.ACCEPTED, Limit.of(50));
        ids.forEach(dispatcher::dispatch);
        return ids.size();
    }

    private void expireStaleProcessing(AssistantChatConversation conversation) {
        LocalDateTime staleBefore = LocalDateTime.now(clock).minus(properties.streamTimeout());
        for (AssistantChatTurn stale : turnRepository.findProcessing(conversation.getId(), PROCESSING_STATUSES)) {
            AiRequest request = stale.getAiRequest();
            if (request.isProcessing() && request.getUpdatedAt() != null
                    && !request.getUpdatedAt().isAfter(staleBefore)
                    && aiRequestRepository.failProcessing(request.getId(), request.getProcessingToken()) == 1) {
                turnRepository.findByAiRequestId(request.getId()).ifPresent(turn -> turn.fail("timeout"));
            }
        }
    }

    private List<ChatAiHistoryMessage> toRecentMessages(List<AssistantChatTurn> newestFirst, int maxMessages) {
        List<ChatAiHistoryMessage> chronological = new ArrayList<>();
        for (int i = newestFirst.size() - 1; i >= 0; i--) {
            AssistantChatTurn turn = newestFirst.get(i);
            chronological.add(ChatAiHistoryMessage.user(turn.getQuestion()));
            if (turn.getAnswer() != null && !turn.getAnswer().isBlank()) {
                chronological.add(ChatAiHistoryMessage.assistant(turn.getAnswer()));
            }
        }
        if (chronological.size() <= maxMessages) return List.copyOf(chronological);
        return List.copyOf(chronological.subList(chronological.size() - maxMessages, chronological.size()));
    }

    private AssistantChatTurnResponse toResponse(AssistantChatTurn turn) {
        JsonNode draft = null;
        List<Long> referenced = List.of();
        try {
            if (turn.getMeetingDraft() != null) draft = objectMapper.readTree(turn.getMeetingDraft());
            if (turn.getReferencedMeetingIds() != null) {
                referenced = objectMapper.readerForListOf(Long.class).readValue(turn.getReferencedMeetingIds());
            }
        } catch (Exception e) {
            log.error("저장된 assistant meetingDraft JSON을 읽지 못했습니다. aiRequestId={}",
                    turn.getAiRequest().getId(), e);
        }
        return new AssistantChatTurnResponse(turn.getAiRequest().getId(), turn.getId(), turn.getQuestion(),
                turn.getAnswer(), turn.getAiRequest().getStatus(), turn.getFailureCode(), draft,
                referenced, turn.getCreatedAt(), turn.getUpdatedAt());
    }

    private String validateMeetingDraft(JsonNode rawDraft, Long teamId) {
        if (rawDraft == null || rawDraft.isNull() || !rawDraft.isObject()) return null;
        try {
            MeetingCreateRequest request = objectMapper.treeToValue(rawDraft, MeetingCreateRequest.class);
            if (!validator.validate(request).isEmpty()) return null;
            if (request.attendeeTeamMemberIds() == null || request.customAttendeeNames() == null) return null;
            if (request.attendeeTeamMemberIds().stream().anyMatch(value -> value == null)
                    || request.attendeeTeamMemberIds().stream().distinct().count()
                    != request.attendeeTeamMemberIds().size()) return null;
            if (!request.attendeeTeamMemberIds().isEmpty()
                    && teamMemberRepository.findAllByTeamIdAndIdInAndMembershipStatus(
                    teamId, request.attendeeTeamMemberIds(), MembershipStatus.ACTIVE).size()
                    != request.attendeeTeamMemberIds().size()) return null;
            int teamAttendees = request.attendeeTeamMemberIds() == null ? 0 : request.attendeeTeamMemberIds().size();
            int customAttendees = request.customAttendeeNames() == null ? 0 : request.customAttendeeNames().size();
            int total = teamAttendees + customAttendees;
            if (total < MeetingAttendeePolicy.MIN_ATTENDEES || total > MeetingAttendeePolicy.MAX_ATTENDEES
                    || customAttendees > MeetingAttendeePolicy.MAX_CUSTOM_ATTENDEES
                    || request.attendeeTeamMemberIds() != null
                    && request.attendeeTeamMemberIds().stream().anyMatch(value -> value == null)
                    || request.customAttendeeNames() != null
                    && request.customAttendeeNames().stream().anyMatch(value -> value == null || value.isBlank()
                    || value.trim().length() > MeetingAttendeePolicy.MAX_CUSTOM_NAME_LENGTH)) {
                return null;
            }
            return objectMapper.writeValueAsString(objectMapper.valueToTree(request));
        } catch (Exception e) {
            log.warn("필수값이 빠졌거나 MeetingCreateRequest와 호환되지 않는 meetingDraft를 전달하지 않습니다.");
            return null;
        }
    }

    private String canonicalIdempotencyKey(Long teamId, Long teamMemberId, String clientKey) {
        try {
            String scoped = teamId + ":" + teamMemberId + ":" + clientKey;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(scoped.getBytes(StandardCharsets.UTF_8));
            return "global:" + HexFormat.of().formatHex(digest);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private String normalizeFailureCode(String code) {
        if (code == null || code.isBlank()) return "generation_failed";
        return switch (code) {
            case "invalid_request", "forbidden", "timeout", "retrieval_failed", "generation_failed",
                    "execution_limit_exceeded" -> code;
            default -> "generation_failed";
        };
    }
}
