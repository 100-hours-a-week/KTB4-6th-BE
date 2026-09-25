package com.backend.meety.domain.transcript.service;

import com.backend.meety.domain.ai.realtime.AiTranscriptSegmentMessage;
import com.backend.meety.domain.meeting.entity.MeetingParticipant;
import com.backend.meety.domain.meeting.entity.Meeting;
import com.backend.meety.domain.meeting.exception.MeetingErrorCode;
import com.backend.meety.domain.meeting.exception.MeetingException;
import com.backend.meety.domain.meeting.repository.MeetingParticipantRepository;
import com.backend.meety.domain.meeting.repository.MeetingRepository;
import com.backend.meety.domain.meeting.service.MeetingService;
import com.backend.meety.domain.team.entity.TeamMember;
import com.backend.meety.domain.team.exception.TeamErrorCode;
import com.backend.meety.domain.team.exception.TeamException;
import com.backend.meety.domain.team.repository.TeamMemberRepository;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSegmentListResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingDetailResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingParticipantResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingRequest;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerMappingStateResponse;
import com.backend.meety.domain.transcript.dto.TranscriptSpeakerResponse;
import com.backend.meety.domain.transcript.entity.TranscriptSegment;
import com.backend.meety.domain.transcript.entity.TranscriptSpeaker;
import com.backend.meety.domain.transcript.event.TranscriptCreatedEvent;
import com.backend.meety.domain.transcript.exception.TranscriptErrorCode;
import com.backend.meety.domain.transcript.exception.TranscriptException;
import com.backend.meety.domain.transcript.repository.TranscriptSegmentRepository;
import com.backend.meety.domain.transcript.repository.TranscriptSpeakerRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptService {

    private static final int MIN_KEYWORD_LENGTH = 2;
    private static final int MAX_KEYWORD_LENGTH = 20;

    private final TranscriptSegmentRepository transcriptSegmentRepository;
    private final TranscriptSpeakerRepository transcriptSpeakerRepository;
    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final MeetingService meetingService;
    private final TeamMemberRepository teamMemberRepository;
    private final Clock clock;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional
    public void saveFinalSegment(AiTranscriptSegmentMessage message) {
        String sourceSegmentKey = message.sourceSegmentKey();
        if (transcriptSegmentRepository.existsBySourceSegmentKey(sourceSegmentKey)) {
            log.debug("중복 transcript segment 저장을 건너뜁니다. meetingId={}, sourceSegmentKey={}",
                    message.meetingId(), sourceSegmentKey);
            return;
        }

        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(message.meetingId())
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        TranscriptSegment segment = TranscriptSegment.createFinal(
                meeting,
                sourceSegmentKey,
                message.payload().sequenceNumber(),
                message.payload().content(),
                message.payload().startedAtMs(),
                message.payload().endedAtMs(),
                message.payload().recognizedAt()
        );

        TranscriptSegment savedSegment;
        try {
            savedSegment = transcriptSegmentRepository.saveAndFlush(segment);
        } catch (DataIntegrityViolationException e) {
            log.debug("중복 transcript segment 저장을 건너뜁니다. meetingId={}, sourceSegmentKey={}",
                    message.meetingId(), sourceSegmentKey);
            return;
        }

        log.debug("TranscriptSegment 저장 완료. transcriptSegmentId={}, meetingId={}, sequenceNumber={}, sourceSegmentKey={}",
                savedSegment.getId(), savedSegment.getMeeting().getId(), savedSegment.getSequenceNumber(),
                savedSegment.getSourceSegmentKey());
        eventPublisher.publishEvent(new TranscriptCreatedEvent(
                savedSegment.getMeeting().getId(),
                savedSegment.getId(),
                savedSegment.getSequenceNumber(),
                savedSegment.getContent(),
                savedSegment.getStartedAtMs(),
                savedSegment.getEndedAtMs(),
                savedSegment.getRecognizedAt()
        ));
        log.debug("TranscriptCreatedEvent 발행. transcriptSegmentId={}, meetingId={}",
                savedSegment.getId(), savedSegment.getMeeting().getId());
    }

    @Transactional(readOnly = true)
    public TranscriptSegmentListResponse getTranscripts(Long userId, Long meetingId) {
        return getTranscripts(userId, meetingId, null);
    }

    @Transactional(readOnly = true)
    public TranscriptSegmentListResponse getTranscripts(Long userId, Long meetingId, String keyword) {
        String normalizedKeyword = normalizeKeyword(keyword);
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        meetingService.validateMeetingAccess(userId, meeting.getTeam().getId());

        List<TranscriptSegment> segments = normalizedKeyword == null
                ? transcriptSegmentRepository.findAllByMeetingIdOrderBySequence(meetingId)
                : transcriptSegmentRepository.searchByMeetingIdAndContent(meetingId, normalizedKeyword);

        List<TranscriptSegmentResponse> responses = segments.stream()
                .map(TranscriptSegmentResponse::from)
                .toList();
        return new TranscriptSegmentListResponse(responses);
    }

    @Transactional
    public TranscriptSpeakerResponse updateSpeakerMapping(
            Long userId,
            Long meetingId,
            Long transcriptSpeakerId,
            TranscriptSpeakerMappingRequest request
    ) {
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        Long teamId = meeting.getTeam().getId();
        meetingService.validateMeetingAccess(userId, teamId);

        TranscriptSpeaker speaker = transcriptSpeakerRepository
                .findByIdAndMeetingIdAndDeletedAtIsNull(transcriptSpeakerId, meetingId)
                .orElseThrow(() -> new TranscriptException(TranscriptErrorCode.TRANSCRIPT_SPEAKER_NOT_FOUND));

        String customAlias = normalizeCustomAlias(request.customAlias());
        if (request.teamMemberId() != null && customAlias != null) {
            throw new TranscriptException(TranscriptErrorCode.INVALID_SPEAKER_MAPPING);
        }

        if (request.teamMemberId() != null) {
            TeamMember teamMember = teamMemberRepository.findById(request.teamMemberId())
                    .orElseThrow(() -> new TeamException(TeamErrorCode.TEAM_MEMBER_NOT_FOUND));
            validateTeamMemberParticipatedInMeeting(meetingId, teamMember);
            speaker.mapToTeamMember(teamMember);
            return TranscriptSpeakerResponse.from(speaker);
        }

        if (customAlias != null) {
            speaker.mapToCustomAlias(customAlias);
            return TranscriptSpeakerResponse.from(speaker);
        }

        speaker.clearMapping();
        return TranscriptSpeakerResponse.from(speaker);
    }

    @Transactional(readOnly = true)
    public TranscriptSpeakerMappingDetailResponse getSpeakerMapping(
            Long userId,
            Long meetingId,
            Long segmentId
    ) {
        Meeting meeting = meetingRepository.findByIdAndDeletedAtIsNull(meetingId)
                .orElseThrow(() -> new MeetingException(MeetingErrorCode.MEETING_NOT_FOUND));
        meetingService.validateMeetingAccess(userId, meeting.getTeam().getId());

        TranscriptSegment segment = transcriptSegmentRepository.findByIdAndMeetingIdWithSpeaker(segmentId, meetingId)
                .orElseThrow(() -> new TranscriptException(TranscriptErrorCode.TRANSCRIPT_SEGMENT_NOT_FOUND));
        TranscriptSpeaker speaker = segment.getTranscriptSpeaker();
        if (speaker == null) {
            throw new TranscriptException(TranscriptErrorCode.TRANSCRIPT_SPEAKER_NOT_FOUND);
        }

        List<TranscriptSpeakerMappingParticipantResponse> participants = meetingParticipantRepository
                .findAllByMeetingIdWithTeamMember(meetingId)
                .stream()
                .map(TranscriptSpeakerMappingParticipantResponse::from)
                .toList();

        return new TranscriptSpeakerMappingDetailResponse(
                TranscriptSpeakerMappingStateResponse.from(speaker),
                participants
        );
    }

    private void validateTeamMemberParticipatedInMeeting(Long meetingId, TeamMember teamMember) {
        if (meetingParticipantRepository.findByMeetingIdAndTeamMemberId(meetingId, teamMember.getId()).isEmpty()) {
            throw new TranscriptException(TranscriptErrorCode.TEAM_MEMBER_NOT_MEETING_PARTICIPANT);
        }
    }

    private String normalizeCustomAlias(String customAlias) {
        if (customAlias == null) {
            return null;
        }
        String trimmedAlias = customAlias.trim();
        if (trimmedAlias.isBlank()) {
            throw new TranscriptException(TranscriptErrorCode.INVALID_SPEAKER_MAPPING);
        }
        return trimmedAlias;
    }

    private String normalizeKeyword(String keyword) {
        if (keyword == null) {
            return null;
        }
        String trimmedKeyword = keyword.trim();
        if (trimmedKeyword.isBlank()) {
            return null;
        }
        if (trimmedKeyword.length() < MIN_KEYWORD_LENGTH || trimmedKeyword.length() > MAX_KEYWORD_LENGTH) {
            throw new TranscriptException(TranscriptErrorCode.INVALID_TRANSCRIPT_KEYWORD);
        }
        return trimmedKeyword;
    }
}
