package com.backend.meety.domain.meeting.service;

import com.backend.meety.domain.ai.assistant.AssistantChatErrorCode;
import com.backend.meety.domain.ai.assistant.AssistantChatException;
import com.backend.meety.domain.ai.repository.AssistantChatTurnRepository;
import com.backend.meety.domain.meeting.dto.MeetingCreateRequest;
import com.backend.meety.domain.meeting.dto.MeetingCreateResponse;
import com.backend.meety.domain.meeting.entity.AssistantMeetingCreation;
import com.backend.meety.domain.meeting.repository.AssistantMeetingCreationRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamRepository;
import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AssistantMeetingCreationService {

    private final TeamRepository teamRepository;
    private final MeetingRepository meetingRepository;
    private final AssistantChatTurnRepository turnRepository;
    private final AssistantMeetingCreationRepository creationRepository;
    private final MeetingParticipantRepository participantRepository;
    private final MeetingService meetingService;
    private final ObjectMapper objectMapper;

    @Transactional
    public MeetingCreateResponse createAfterConfirmation(Long userId, Long teamId, Long aiRequestId,
                                                         MeetingCreateRequest request) {
        teamRepository.findByIdForUpdate(teamId)
                .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_NOT_FOUND));
        var turn = turnRepository.findCompletedDraftForConfirmation(aiRequestId, teamId, userId)
                .orElseThrow(() -> new AssistantChatException(AssistantChatErrorCode.DRAFT_NOT_CONFIRMED));
        String requestHash = hash(request);
        try {
            MeetingCreateRequest approved = objectMapper.readValue(turn.getMeetingDraft(), MeetingCreateRequest.class);
            if (!approved.equals(request)) {
                throw new AssistantChatException(AssistantChatErrorCode.DRAFT_NOT_CONFIRMED);
            }
        } catch (AssistantChatException e) {
            throw e;
        } catch (Exception e) {
            throw new AssistantChatException(AssistantChatErrorCode.DRAFT_NOT_CONFIRMED);
        }

        var existing = creationRepository.findByAiRequestIdWithMeeting(aiRequestId);
        if (existing.isPresent()) {
            AssistantMeetingCreation creation = existing.get();
            if (!creation.getRequestHash().equals(requestHash)) {
                throw new AssistantChatException(AssistantChatErrorCode.DRAFT_NOT_CONFIRMED);
            }
            return MeetingCreateResponse.from(creation.getMeeting(),
                    participantRepository.findAttendeesByMeetingId(creation.getMeeting().getId()));
        }

        MeetingCreateResponse created = meetingService.createMeeting(userId, teamId, request);
        var meeting = meetingRepository.findByIdAndDeletedAtIsNull(created.meetingId())
                .orElseThrow(() -> new AssistantChatException(AssistantChatErrorCode.DRAFT_NOT_CONFIRMED));
        creationRepository.save(AssistantMeetingCreation.create(turn.getAiRequest(), meeting, requestHash));
        return created;
    }

    private String hash(MeetingCreateRequest request) {
        try {
            byte[] canonical = objectMapper.writeValueAsBytes(request);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (Exception e) {
            throw new IllegalStateException("Unable to hash meeting creation request", e);
        }
    }
}
